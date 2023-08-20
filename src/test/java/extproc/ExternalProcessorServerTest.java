package extproc;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import extproc.processors.NoOpRequestProcessor;

class ServerTest {
    @Test void serverStartsWithoutException() throws IOException, InterruptedException {
        ExternalProcessorServer server = new ExternalProcessorServer().builder()
            .addRequestProcessor(new NoOpRequestProcessor()).start();
        assertNotNull(server);
        server.stop();
    }
}
