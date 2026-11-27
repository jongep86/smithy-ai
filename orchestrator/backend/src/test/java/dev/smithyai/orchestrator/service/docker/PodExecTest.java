package dev.smithyai.orchestrator.service.docker;

import static org.junit.jupiter.api.Assertions.*;

import io.fabric8.kubernetes.client.KubernetesClientException;
import java.net.http.WebSocketHandshakeException;
import org.junit.jupiter.api.Test;

class PodExecTest {

    /**
     * What fabric8 7.9 throws when the API server refuses the exec upgrade,
     * as seen with a Role that grants {@code create} but not {@code get} on
     * {@code pods/exec}. The status code is three causes down.
     */
    @Test
    void aRefusedExecUpgradeNamesItsHttpStatus() {
        var status = new RuntimeException("Unexpected HTTP response status code 403");
        var handshake = new WebSocketHandshakeException(null).initCause(status);
        var error = new KubernetesClientException("An error has occurred.", handshake);

        assertEquals(
            "KubernetesClientException: An error has occurred." +
                "; caused by WebSocketHandshakeException: null" +
                "; caused by RuntimeException: Unexpected HTTP response status code 403",
            PodExec.describe(error)
        );
    }

    @Test
    void aPlainExceptionIsJustNamed() {
        assertEquals("IllegalStateException: closed", PodExec.describe(new IllegalStateException("closed")));
    }

    @Test
    void repeatedCausesAppearOnce() {
        var inner = new RuntimeException("boom");
        var outer = new RuntimeException("boom", inner);

        assertEquals("RuntimeException: boom", PodExec.describe(outer));
    }
}
