package com.fragpicker.ingestion.preview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fragpicker.integration.parsevideo.*;
import com.fragpicker.integration.media.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.URI;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named="DB_TEST_URL",matches=".+")
class PreviewIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PreviewParser parser;
    @MockitoBean MediaResourceProbe probe;
    private final List<Long> owners=new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username",()->System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password",()->System.getenv("DB_TEST_PASSWORD"));
    }
    @BeforeEach void media() {
        when(parser.parse(anyString())).thenReturn(new ParsedVideo("学习导数\u200B",URI.create("https://media.example.com/video?signature=private"),null,"数学老师",null,null));
        when(probe.check(any(),any())).thenReturn("video/mp4");
    }
    @AfterEach void cleanup(){owners.forEach(id->jdbc.update("DELETE FROM users WHERE id=?",id));}
    @Test void requiresAuthenticationAndRejectsInvalidSharesBeforeCallingProviders() {
        assertThat(http.postForEntity("/api/v1/fragments/preview",Map.of("shareText","https://b23.tv/test"),String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var user=account();
        for(String link:List.of("ordinary clipboard text","http://127.0.0.1/private","https://example.com/article","https://b23.tv/a https://b23.tv/b"))
            assertThat(preview(user,link).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(parser,probe); assertEmpty(user);
    }
    @Test void returnsBoundedMetadataAfterReadingVideoWithoutCreatingTasksOrExposingMediaUrls() {
        var user=account(); var response=preview(user,"课程 https://b23.tv/test。");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody().path("resourceAvailable").asBoolean()).isTrue();
        assertThat(response.getBody().path("sourceUrl").asText()).isEqualTo("https://b23.tv/test");
        assertThat(response.getBody().path("title").asText()).isEqualTo("学习导数");
        assertThat(response.getBody().toString()).doesNotContain("signature","media.example.com");
        verify(probe).check(URI.create("https://media.example.com/video?signature=private"),URI.create("https://b23.tv/test"));
        assertEmpty(user);
    }
    @Test void unavailableResourceAndUnsupportedContentNeverCreateIngestionRows() {
        var first=account(); when(probe.check(any(),any())).thenThrow(new MediaDownloadFailure(MediaDownloadFailure.Code.SOURCE_EXPIRED,false));
        assertThat(preview(first,"https://b23.tv/expired").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY); assertEmpty(first);
        var second=account(); when(parser.parse(anyString())).thenThrow(new ParseVideoFailure(ParseVideoFailure.Code.UNSUPPORTED_CONTENT,false));
        assertThat(preview(second,"https://b23.tv/images").getBody().path("code").asText()).isEqualTo("PREVIEW_UNSUPPORTED"); assertEmpty(second);
    }
    @Test void repeatedPreviewsAreLimitedPerAuthenticatedUser() {
        var first=account(); var second=account();
        assertThat(preview(first,"https://b23.tv/one").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preview(first,"https://b23.tv/two").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(preview(second,"https://b23.tv/two").getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(parser,times(2)).parse(anyString()); assertEmpty(first); assertEmpty(second);
    }
    private ResponseEntity<JsonNode> preview(Account user,String text) {
        var headers=new HttpHeaders(); headers.setBearerAuth(user.token());headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("/api/v1/fragments/preview",new HttpEntity<>(Map.of("shareText",text),headers),JsonNode.class);
    }
    private Account account() {
        var credentials=Map.of("username","preview_"+UUID.randomUUID().toString().replace("-","").substring(0,12),"password","Integration-password-123");
        assertThat(http.postForEntity("/api/v1/auth/register",credentials,String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var login=http.postForEntity("/api/v1/auth/login",credentials,JsonNode.class).getBody();
        var account=new Account(login.path("user").path("id").asLong(),login.path("accessToken").asText()); owners.add(account.id());return account;
    }
    private void assertEmpty(Account user) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragments WHERE user_id=?",Integer.class,user.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_jobs WHERE user_id=?",Integer.class,user.id())).isZero();
    }
    private record Account(long id,String token) {}
}
