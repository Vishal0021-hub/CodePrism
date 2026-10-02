package codeprism.backend;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Requires live PostgreSQL instance on localhost:5433")
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}

