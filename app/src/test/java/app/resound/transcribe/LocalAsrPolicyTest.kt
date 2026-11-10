package app.resound.transcribe

import app.resound.transcribe.local.LocalModels
import org.junit.Assert.*
import org.junit.Test

class LocalAsrPolicyTest {
    @Test fun rootAndV1ResolveWithoutDuplicateVersion() {
        assertEquals(listOf("https://example.com/v1/audio/transcriptions","https://example.com/audio/transcriptions"),OpenAiEndpoint.transcriptions(" https://example.com/ "))
        assertEquals(listOf("https://example.com/v1/audio/transcriptions"),OpenAiEndpoint.transcriptions("https://example.com/v1/"))
    }
    @Test fun fullEndpointsAndProxyPrefixesArePreserved() {
        assertEquals(listOf("https://example.com/proxy/v1"),OpenAiEndpoint.bases("https://example.com/proxy/v1/audio/transcriptions"))
        assertEquals(listOf("https://example.com/v1"),OpenAiEndpoint.bases("https://example.com/v1/chat/completions"))
        assertEquals("https://example.com/proxy/v1",OpenAiEndpoint.bases("https://example.com/proxy").first())
    }
    @Test fun rejectCredentialsAndInvalidSchemes() {
        // 上游 055 已允许 HTTP（并区分内网/公网）；这里仍拒绝无效协议和 URL 内凭据。
        for(url in listOf("ftp://example.com","https://key@example.com","https://example.com?key=secret"))assertThrows(IllegalArgumentException::class.java){OpenAiEndpoint.bases(url)}
        assertEquals(EndpointKind.PublicHttp, OpenAiEndpoint.kind("http://example.com"))
    }
    @Test fun onlyValidatedSocMayUseNpuPackage() {
        assertTrue(LocalModels.supportedSoc("SM8650"));assertFalse(LocalModels.supportedSoc("SM8550"));assertFalse(LocalModels.supportedSoc("unknown"))
    }
}
