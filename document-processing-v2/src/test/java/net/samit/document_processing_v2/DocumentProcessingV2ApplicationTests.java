package net.samit.document_processing_v2;

import net.samit.document_processing_v2.consumer.FileEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DocumentProcessingV2ApplicationTests {

	@MockitoBean
	FileEventListener fileEventListener;

	@Test
	void contextLoads() {
	}

}
