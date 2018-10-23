package in.erail.plantuml;

import com.amazonaws.services.s3.AmazonS3;
import com.google.common.hash.Hashing;
import com.google.common.net.HttpHeaders;
import in.erail.glue.Glue;
import in.erail.server.Server;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.Rule;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class PlantUMLServiceTest {

    @Rule
    public Timeout rule = Timeout.seconds(2000);

    @Test
    public void testGetRequest(TestContext context) {

        Async async = context.async();
        Server server = Glue.instance().resolve("/in/erail/server/Server");
        AmazonS3 s3 = Glue.instance().resolve("/com/amazonaws/services/s3/AmazonS3");
        PlantUMLService service = Glue.instance().resolve("/in/erail/plantuml/PlantUMLService");
        String url = "/v1/plantuml/image?u=/sequence-diagrams/001-refresh-coin-market-prices.plantuml";
        final String nameHash = Hashing.sha256().hashString("/sequence-diagrams/001-refresh-coin-market-prices.plantuml", StandardCharsets.UTF_8).toString();

        server
                .getVertx()
                .createHttpClient()
                .get(server.getHttpServerOptions().getPort(), server.getHttpServerOptions().getHost(), url)
                .putHeader(HttpHeaders.REFERER, "https://xch4nge.atlassian.net/test/url")
                .handler(response -> {
                    context.assertEquals(response.statusCode(), HttpResponseStatus.FOUND.code(), response.statusMessage());
                    context.assertNotNull(response.getHeader(HttpHeaders.LOCATION));
                    context.assertNotNull(response.getHeader(HttpHeaders.CACHE_CONTROL));
                    context.assertTrue(s3.doesObjectExist(service.getBucketName(), nameHash));
                    async.countDown();
                })
                .end();
    }

    @Test
    public void testGetRequestWithParam(TestContext context) {

        Async async = context.async();
        Server server = Glue.instance().<Server>resolve("/in/erail/server/Server");
        AmazonS3 s3 = Glue.instance().resolve("/com/amazonaws/services/s3/AmazonS3");
        PlantUMLService service = Glue.instance().resolve("/in/erail/plantuml/PlantUMLService");
        String url = "/v1/plantuml/image?u=/sequence-diagrams/001-refresh-coin-market-prices.plantuml&v=1";
        final String nameHash = Hashing.sha256().hashString("/sequence-diagrams/001-refresh-coin-market-prices.plantuml:1", StandardCharsets.UTF_8).toString();

        server
                .getVertx()
                .createHttpClient()
                .get(server.getHttpServerOptions().getPort(), server.getHttpServerOptions().getHost(), url)
                .putHeader(HttpHeaders.REFERER, "https://xch4nge.atlassian.net/test/url")
                .handler(response -> {
                    context.assertEquals(response.statusCode(), HttpResponseStatus.FOUND.code(), response.statusMessage());
                    context.assertNotNull(response.getHeader(HttpHeaders.LOCATION));
                    context.assertNotNull(response.getHeader(HttpHeaders.CACHE_CONTROL));
                    context.assertTrue(s3.doesObjectExist(service.getBucketName(), nameHash));
                    async.countDown();
                })
                .end();
    }

}
