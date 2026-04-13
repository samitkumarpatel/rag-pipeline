package dev.samitkumar.ragpipeline.tracking.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PipelineJobRepository extends JpaRepository<PipelineJobRecord, UUID> {}
