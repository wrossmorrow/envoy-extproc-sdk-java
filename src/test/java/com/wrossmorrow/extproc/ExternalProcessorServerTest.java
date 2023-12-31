package com.github.wrossmorrow.extproc;

import static org.junit.jupiter.api.Assertions.*;

import com.github.wrossmorrow.extproc.processors.NoOpRequestProcessor;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ServerTest {
  @Test
  void serverStartsWithoutException() throws IOException, InterruptedException {
    ExternalProcessorServer server =
        new ExternalProcessorServer()
            .builder()
            .addRequestProcessor(new NoOpRequestProcessor())
            .start();
    assertNotNull(server);
    server.stop();
  }
}
