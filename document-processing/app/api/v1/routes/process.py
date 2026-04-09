"""
Document processing API endpoints.

POST /api/v1/process/file
    Dispatches a file to the correct Celery queue based on MIME type.
    Returns immediately with task_id — caller polls for completion.

GET  /api/v1/process/status/{task_id}
    Returns current Celery task status and result when available.

POST /api/v1/process/event
    Accepts a FileUploadedEvent JSON body (from RabbitMQ via webhook
    or direct POST). Dispatches to the correct queue.
"""
from __future__ import annotations

from typing import Annotated

import structlog
from celery.result import AsyncResult
from fastapi import APIRouter, Depends, HTTPException, status

from app.core.celery_app import celery_app
from app.core.config import Settings, get_settings
from app.models.domain import (
    FileUploadedEvent,
    ProcessFileRequest,
    ProcessFileResponse,
    ProcessingStatus,
    TaskStatusResponse,
)
from app.utils.mime_router import get_route, supported_mime_types

logger = structlog.get_logger(__name__)
router = APIRouter(prefix="/api/v1/process", tags=["processing"])

SettingsDep = Annotated[Settings, Depends(get_settings)]


@router.post(
    "/file",
    response_model=ProcessFileResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="Dispatch a file for processing",
)
def dispatch_file(body: ProcessFileRequest, settings: SettingsDep) -> ProcessFileResponse:
    """
    Dispatch a file to the appropriate Celery queue based on MIME type.

    Returns immediately with a task_id. Poll GET /status/{task_id} for result.
    """
    route = get_route(body.mime_type)
    if route is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail={
                "message": f"Unsupported MIME type: {body.mime_type}",
                "supported_types": supported_mime_types(),
            },
        )

    task = celery_app.send_task(
        route.task_name,
        kwargs={
            "file_id": str(body.file_id),
            "job_id": str(body.job_id),
            "stored_path": body.stored_path,
            "source_filename": body.original_filename,
        },
        queue=route.queue,
    )

    logger.info(
        "file_dispatched",
        file_id=str(body.file_id),
        job_id=str(body.job_id),
        mime_type=body.mime_type,
        queue=route.queue,
        task_id=task.id,
    )

    return ProcessFileResponse(
        task_id=task.id,
        file_id=body.file_id,
        job_id=body.job_id,
        status=ProcessingStatus.PENDING,
        queue=route.queue,
        message=f"File dispatched to queue '{route.queue}'",
    )


@router.post(
    "/event",
    response_model=ProcessFileResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="Process a FileUploadedEvent from the Ingestion Service",
)
def handle_file_event(event: FileUploadedEvent, settings: SettingsDep) -> ProcessFileResponse:
    """
    Accept a FileUploadedEvent (published by the Ingestion Service)
    and dispatch to the correct Celery queue.

    This endpoint is used when consuming events via HTTP push.
    For RabbitMQ-direct consumption, use the Celery consumer instead.
    """
    route = get_route(event.mime_type)
    if route is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Unsupported MIME type: {event.mime_type}",
        )

    import os
    source_filename = os.path.basename(event.original_path)

    task = celery_app.send_task(
        route.task_name,
        kwargs={
            "file_id": str(event.file_id),
            "job_id": str(event.job_id),
            "stored_path": event.stored_path,
            "source_filename": source_filename,
        },
        queue=route.queue,
    )

    logger.info(
        "event_dispatched",
        event_id=str(event.event_id),
        file_id=str(event.file_id),
        mime_type=event.mime_type,
        task_id=task.id,
    )

    return ProcessFileResponse(
        task_id=task.id,
        file_id=event.file_id,
        job_id=event.job_id,
        status=ProcessingStatus.PENDING,
        queue=route.queue,
        message=f"Event dispatched to queue '{route.queue}'",
    )


@router.get(
    "/status/{task_id}",
    response_model=TaskStatusResponse,
    summary="Get processing task status",
)
def get_task_status(task_id: str) -> TaskStatusResponse:
    """
    Return the current status of a Celery processing task.

    States: PENDING → STARTED → SUCCESS / FAILURE
    """
    result: AsyncResult = AsyncResult(task_id, app=celery_app)

    if result.state == "SUCCESS":
        return TaskStatusResponse(
            task_id=task_id,
            status="SUCCESS",
            result=result.get(),
        )
    elif result.state == "FAILURE":
        return TaskStatusResponse(
            task_id=task_id,
            status="FAILURE",
            error=str(result.result),
        )
    else:
        return TaskStatusResponse(task_id=task_id, status=result.state)
