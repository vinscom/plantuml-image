package in.erail.plantuml;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.google.common.base.Strings;
import com.google.common.hash.Hashing;
import com.google.common.net.HttpHeaders;
import in.erail.common.FrameworkConstants;
import in.erail.service.RESTServiceImpl;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.reactivex.Single;
import io.vertx.core.json.JsonObject;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.core.eventbus.Message;
import io.vertx.reactivex.ext.web.client.HttpRequest;
import io.vertx.reactivex.ext.web.client.WebClient;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.core.DiagramDescription;

/**
 *
 * @author vinay
 */
public class PlantUMLService extends RESTServiceImpl {

    private String mGitHubAccessToken;
    private WebClient mWebClient;
    private String mBaseURL;
    private String mFilePathQueryParamName = "u";
    private String mVersionQueryParamName = "v";
    private Pattern mRefererRegEx;
    private AmazonS3 mAmazonS3;
    private String mBucketName;
    private long mSingedURLValidity;
    private String mCacheControl;

    @Override
    public void process(Message<JsonObject> pMessage) {

        getLog().debug(() -> pMessage.body().toString());

        JsonObject requestHeaders = pMessage.body().getJsonObject(FrameworkConstants.RoutingContext.Json.HEADERS);

        final Optional<String> referer;

        if (requestHeaders.containsKey("Referer")) {
            referer = Optional.ofNullable(requestHeaders.getString("Referer"));
        } else {
            referer = Optional.ofNullable(requestHeaders.getString("referer"));
        }

        if (referer.isPresent()) {
            Matcher urlReferer = getRefererRegEx().matcher(referer.get());
            if (!urlReferer.find()) {
                pMessage.reply(buildGenericErrorResponse());
                getLog().debug(() -> "Referer does not match:" + referer.get());
                return;
            }
            getLog().debug(() -> "Referer Match:" + referer.get());
        } else {
            pMessage.reply(buildGenericErrorResponse());
            getLog().debug(() -> "Referer is null");
            return;
        }

        JsonObject queryParams = pMessage.body().getJsonObject(FrameworkConstants.RoutingContext.Json.QUERY_STRING_PARAM);
        Optional<String> path = Optional.ofNullable(queryParams.getString(getFilePathQueryParamName()));
        Optional<String> version = Optional.ofNullable(queryParams.getString(getVersionQueryParamName()));

        if (path.isPresent()) {
            getPlantUMLURL(path, version)
                    .subscribe((URL) -> {
                        JsonObject headers = new JsonObject();
                        headers.put(HttpHeaders.LOCATION, URL.toString());
                        headers.put(HttpHeaders.CACHE_CONTROL, getCacheControl());
                        JsonObject resp = new JsonObject();
                        resp.put(FrameworkConstants.RoutingContext.Json.HEADERS, headers);
                        resp.put(FrameworkConstants.RoutingContext.Json.STATUS_CODE, HttpResponseStatus.FOUND.codeAsText());
                        getLog().debug(() -> resp.toString());
                        pMessage.reply(resp);
                    }, (err) -> {
                        pMessage.reply(buildGenericErrorResponse());
                        getLog().error(() -> err);
                    });
        } else {
            pMessage.reply(buildGenericErrorResponse());
            getLog().debug(() -> "File path is missing in query param");
        }
    }

    public JsonObject buildGenericErrorResponse() {
        JsonObject resp = new JsonObject();
        resp.put(FrameworkConstants.RoutingContext.Json.STATUS_CODE, HttpResponseStatus.BAD_REQUEST.codeAsText());
        return resp;
    }

    public Single<URL> getPlantUMLURL(Optional<String> pPath, Optional<String> pVersion) {

        final String name;

        if (pVersion.isPresent()) {
            name = pPath.get() + ":" + pVersion.get();
        } else {
            name = pPath.get();
        }

        final String nameHash = Hashing.sha256().hashString(name, StandardCharsets.UTF_8).toString();

        final String fullPath = getBaseURL() + pPath.get();

        return getVertx()
                .<Boolean>rxExecuteBlocking((event) -> event.complete(getAmazonS3().doesObjectExist(getBucketName(), nameHash)))
                .flatMap((filePresentInS3) -> {
                    if (filePresentInS3) {
                        return getPublicURL(nameHash);
                    }
                    return getUmlTxtFromGithub(fullPath)
                            .flatMap(this::generateUmlImage)
                            .flatMap((umlImgPath) -> {
                                return tryUploadToS3(getBucketName(), nameHash, umlImgPath.toFile());
                            })
                            .flatMap((putResult) -> {
                                getLog().debug(() -> putResult.toString());
                                return getPublicURL(nameHash);
                            });
                });
    }

    public Single<Path> generateUmlImage(String pUmlTxt) {
        return getVertx()
                .<Path>rxExecuteBlocking((event) -> {
                    try {
                        Path umlImgFile = Files.createTempFile("uml-", ".png");
                        SourceStringReader umlTxtReader = new SourceStringReader(pUmlTxt);
                        DiagramDescription desc = umlTxtReader.outputImage(umlImgFile.toFile());
                        Optional isCreated = Optional.ofNullable(desc.getDescription());
                        if (isCreated.isPresent()) {
                            event.complete(umlImgFile);
                            return;
                        }
                    } catch (IOException ex) {
                        getLog().error(ex);
                    }
                    throw new RuntimeException("Not able to create file in Temp");
                });
    }

    public Single<URL> getPublicURL(String pName) {
        return getVertx()
                .<URL>rxExecuteBlocking((event) -> {
                    Date expiration = new Date();
                    long expTimeMillis = expiration.getTime();
                    expTimeMillis += getSingedURLValidity();
                    expiration.setTime(expTimeMillis);

                    GeneratePresignedUrlRequest generatePresignedUrlRequest
                            = new GeneratePresignedUrlRequest(getBucketName(), pName)
                                    .withMethod(HttpMethod.GET)
                                    .withExpiration(expiration);

                    event.complete(getAmazonS3().generatePresignedUrl(generatePresignedUrlRequest));
                });
    }

    public Single<String> getUmlTxtFromGithub(String pPath) {

        HttpRequest<Buffer> request = getWebClient().getAbs(pPath);

        if (!Strings.isNullOrEmpty(getGitHubAccessToken())) {
            request.putHeader(HttpHeaders.AUTHORIZATION, "Bearer " + getGitHubAccessToken());
        }

        return request
                .rxSend()
                .map((resp) -> {
                    if(resp.statusCode() == 200){
                        return resp.bodyAsString();
                    }
                    getLog().error("Not able to process request:" + pPath);
                    throw new RuntimeException("Not able to process request" + pPath);
                });
    }

    public Single<PutObjectResult> tryUploadToS3(String pBucketName, String pName, File pFile) {
        return getVertx()
                .<PutObjectResult>rxExecuteBlocking((event) -> {
                    PutObjectRequest request = new PutObjectRequest(pBucketName, pName, pFile);
                    ObjectMetadata metadata = new ObjectMetadata();
                    metadata.setCacheControl(getCacheControl());
                    metadata.setContentType("image/png");
                    request.setMetadata(metadata);
                    PutObjectResult putResponse = getAmazonS3().putObject(request);
                    try {
                        Files.delete(pFile.toPath());
                    } catch (IOException ex) {
                        getLog().error(ex);
                    }
                    event.complete(putResponse);
                });
    }

    public String getGitHubAccessToken() {
        return mGitHubAccessToken;
    }

    public void setGitHubAccessToken(String pGitHubAccessToken) {
        this.mGitHubAccessToken = pGitHubAccessToken;
    }

    public WebClient getWebClient() {
        return mWebClient;
    }

    public void setWebClient(WebClient pWebClient) {
        this.mWebClient = pWebClient;
    }

    public String getBaseURL() {
        return mBaseURL;
    }

    public void setBaseURL(String pBaseURL) {
        this.mBaseURL = pBaseURL;
    }

    public String getFilePathQueryParamName() {
        return mFilePathQueryParamName;
    }

    public void setFilePathQueryParamName(String pFilePathQueryParamName) {
        this.mFilePathQueryParamName = pFilePathQueryParamName;
    }

    public Pattern getRefererRegEx() {
        return mRefererRegEx;
    }

    public void setRefererRegEx(Pattern pRefererRegEx) {
        this.mRefererRegEx = pRefererRegEx;
    }

    public AmazonS3 getAmazonS3() {
        return mAmazonS3;
    }

    public void setAmazonS3(AmazonS3 pAmazonS3) {
        this.mAmazonS3 = pAmazonS3;
    }

    public String getBucketName() {
        return mBucketName;
    }

    public void setBucketName(String pBucketName) {
        this.mBucketName = pBucketName;
    }

    public long getSingedURLValidity() {
        return mSingedURLValidity;
    }

    public void setSingedURLValidity(long pSingedURLValidity) {
        this.mSingedURLValidity = pSingedURLValidity;
    }

    public String getCacheControl() {
        return mCacheControl;
    }

    public void setCacheControl(String pCacheControl) {
        this.mCacheControl = pCacheControl;
    }

    public String getVersionQueryParamName() {
        return mVersionQueryParamName;
    }

    public void setVersionQueryParamName(String pVersionQueryParamName) {
        this.mVersionQueryParamName = pVersionQueryParamName;
    }

}
