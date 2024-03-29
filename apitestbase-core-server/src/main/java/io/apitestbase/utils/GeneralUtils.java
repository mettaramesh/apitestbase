package io.apitestbase.utils;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Encoding;
import com.github.tomakehurst.wiremock.http.LoggedResponse;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.matching.*;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.github.tomakehurst.wiremock.verification.notmatched.PlainTextStubNotMatchedRenderer;
import io.apitestbase.core.teststep.HTTPAPIResponse;
import io.apitestbase.db.SQLStatementType;
import io.apitestbase.models.*;
import io.apitestbase.models.mixin.*;
import io.apitestbase.models.teststep.HTTPHeader;
import io.apitestbase.models.teststep.MQRFH2Folder;
import org.antlr.v4.runtime.CharStreams;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.UrlValidator;
import org.apache.hc.client5.http.ClientProtocolException;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.jdbi.v3.core.internal.SqlScriptParser;
import org.w3c.dom.Document;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.xml.transform.TransformerException;
import javax.xml.xpath.XPathExpressionException;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;

import static com.github.tomakehurst.wiremock.common.Metadata.metadata;
import static io.apitestbase.APITestBaseConstants.WIREMOCK_STUB_METADATA_ATTR_NAME_API_TEST_BASE_ID;
import static io.apitestbase.APITestBaseConstants.WIREMOCK_STUB_METADATA_ATTR_NAME_API_TEST_BASE_NUMBER;

public final class GeneralUtils {
    /**
     * @param rs
     * @return a list of lower case column names present in the result set.
     * @throws SQLException
     */
    public static List<String> getFieldsPresentInResultSet(ResultSet rs) throws SQLException {
        List<String> fieldsPresentInResultSet = new ArrayList<String>();
        ResultSetMetaData metaData = rs.getMetaData();
        for(int index =1; index <= metaData.getColumnCount(); index++) {
            fieldsPresentInResultSet.add(metaData.getColumnLabel(index).toLowerCase());
        }
        return fieldsPresentInResultSet;
    }

    public static boolean isSQLRequestSingleSelectStatement(String sqlRequest) {
        List<String> statements = getSqlStatements(sqlRequest);
        return statements.size() == 1 && SQLStatementType.isSelectStatement(statements.get(0));
    }

    /**
     * Parse the sqlRequest to get SQL statements, trimmed and without comments.
     * @param sqlRequest
     * @return
     */
    public static List<String> getSqlStatements(String sqlRequest) {
        final List<String> statements = new ArrayList<>();
        String lastStatement = new SqlScriptParser((t, sb) -> {
            statements.add(sb.toString().trim());
            sb.setLength(0);
        }).parse(CharStreams.fromString(sqlRequest));
        statements.add(lastStatement.trim());
        statements.removeAll(Collections.singleton(""));   //  remove all empty statements

        return statements;
    }

    public static Map<String, String> udpListToMap(List<UDP> testcaseUDPs) {
        Map<String, String> result = new HashMap<>();
        for (UDP udp: testcaseUDPs) {
            result.put(udp.getName(), udp.getValue());
        }
        return result;
    }

    public static void checkDuplicatePropertyNames(Set<String> names, List<String> names2, List<String> names3) {
        Set<String> set = new HashSet<>();
        set.addAll(names);
        for (String name2 : names2) {
            if (!set.add(name2)) {
                throw new RuntimeException("Duplicate property name \"" + name2 + "\" between data tables and/or UDPs");
            }
        }
        for (String name3 : names3) {
            if (!set.add(name3)) {
                throw new RuntimeException("Duplicate property name \"" + name3 + "\" between data tables and/or UDPs");
            }
        }
    }

    public static void checkDuplicatePropertyNameBetweenDataTableAndUPDs(Set<String> udpNames, DataTable dataTable) {
        Set<String> set = new HashSet<>();
        set.addAll(udpNames);
        for (DataTableColumn dataTableColumn : dataTable.getColumns()) {
            if (!set.add(dataTableColumn.getName())) {
                throw new RuntimeException("Duplicate property name between data table and UDPs: " + dataTableColumn.getName());
            }
        }
    }

    /**
     * This method trusts all SSL certificates exposed by the API.
     *
     * @param url
     * @param username
     * @param password
     * @param httpMethod
     * @param httpHeaders
     * @param httpBody
     * @return
     * @throws Exception
     */
    public static HTTPAPIResponse invokeHTTPAPI(String url, String username, String password, HTTPMethod httpMethod,
                                                List<HTTPHeader> httpHeaders, String httpBody, String timeout) throws Exception {

        //  validate parameters
        int hardTimeout = Integer.valueOf(timeout);
        if (hardTimeout < 0) {
            throw new IllegalArgumentException("Timeout can't be negative");
        }
        UrlValidator urlValidator = new UrlValidator(new String[] {"http", "https"}, UrlValidator.ALLOW_LOCAL_URLS);
        if (!urlValidator.isValid(url)) {
            throw new IllegalArgumentException("Invalid URL");
        }

        //  create HTTP request object and set body if applicable
        HttpUriRequest httpRequest;
        switch (httpMethod) {
            case GET:
                httpRequest = new HttpGet(url);
                break;
            case POST:
                HttpPost httpPost = new HttpPost(url);
                httpPost.setEntity(httpBody == null ? null : new StringEntity(httpBody, StandardCharsets.UTF_8));    //  StringEntity doesn't accept null string (exception is thrown)
                httpRequest = httpPost;
                break;
            case PUT:
                HttpPut httpPut = new HttpPut(url);
                httpPut.setEntity(httpBody == null ? null : new StringEntity(httpBody, StandardCharsets.UTF_8));     //  StringEntity doesn't accept null string (exception is thrown)
                httpRequest = httpPut;
                break;
            case DELETE:
                httpRequest = new HttpDelete(url);
                break;
            default:
                throw new IllegalArgumentException("Unrecognized HTTP method " + httpMethod);
        }

        //  set request HTTP headers
        for (HTTPHeader httpHeader : httpHeaders) {
            httpRequest.setHeader(httpHeader.getName(), httpHeader.getValue());
        }
        //  set HTTP basic auth
        if (!"".equals(StringUtils.trimToEmpty(username))) {
            String auth = username + ":" + password;
            String encodedAuth = Base64.encodeBase64String(auth.getBytes());
            String authHeader = "Basic " + encodedAuth;
            httpRequest.setHeader(HttpHeaders.AUTHORIZATION, authHeader);
        }

        //  build HTTP Client instance, trusting all SSL certificates, using system HTTP proxy if needed and exists
        SSLContext sslContext = new SSLContextBuilder().loadTrustMaterial((chain, authType) -> true).build();
        HostnameVerifier allowAllHosts = new NoopHostnameVerifier();
        SSLConnectionSocketFactory connectionFactory = new SSLConnectionSocketFactory(sslContext, allowAllHosts);
        HttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(connectionFactory).build();
        HttpClientBuilder httpClientBuilder = HttpClients.custom().setConnectionManager(cm);
        InetAddress urlHost = InetAddress.getByName(new URL(url).getHost());
        if (!(urlHost.isLoopbackAddress() || urlHost.isSiteLocalAddress())) {    //  only use system proxy for external address
            Proxy systemHTTPProxy = getSystemHTTPProxy();
            if (systemHTTPProxy != null) {
                InetSocketAddress addr = (InetSocketAddress) systemHTTPProxy.address();
                httpClientBuilder.setProxy(new HttpHost(addr.getHostName(), addr.getPort()));
            }
        }
        CloseableHttpClient httpClient = httpClientBuilder.build();

        //  set timeout
        Date[] responseTimeFrame = new Date[2];
        boolean[] timedOut = new boolean[1];
        if (hardTimeout > 0) {
            TimerTask task = new TimerTask() {
                @Override
                public void run() {
                    if (responseTimeFrame[1] == null) {         //  response not received
                        timedOut[0] = true;
                        httpRequest.abort();
                    }
                }
            };
            new Timer(true).schedule(task, hardTimeout * 1000);
        }

        //  invoke the API
        responseTimeFrame[0] = new Date();        //  invocation start time
        final HTTPAPIResponse apiResponse = new HTTPAPIResponse();
        try {
            try (CloseableHttpResponse httpResponse = httpClient.execute(httpRequest)) {
                responseTimeFrame[1] = new Date();     //  response received time
                apiResponse.setStatusCode(httpResponse.getCode());
                StringBuffer statusLine = new StringBuffer();
                statusLine.append(httpResponse.getVersion()).append(' ').append(httpResponse.getCode()).append(' ')
                        .append(httpResponse.getReasonPhrase());
                apiResponse.getHttpHeaders().add(new HTTPHeader("*Status-Line*", statusLine.toString()));
                Header[] headers = httpResponse.getHeaders();
                for (Header header: headers) {
                    apiResponse.getHttpHeaders().add(new HTTPHeader(header.getName(), header.getValue()));
                }
                HttpEntity entity = httpResponse.getEntity();
                apiResponse.setHttpBody(entity != null ? EntityUtils.toString(entity) : null);
            }
        } catch (ClientProtocolException e) {
            throw new RuntimeException(e.getCause().getMessage(), e);
        } catch (RequestFailedException e) {
            if (timedOut[0]) {
                throw new RuntimeException("Timed out. Request is aborted.");
            } else {
                throw e;
            }
        } finally {
            httpClient.close();
        }

        long responseTime = responseTimeFrame[1].getTime() - responseTimeFrame[0].getTime();
        apiResponse.setResponseTime(responseTime);

        return apiResponse;
    }

    public static Proxy getSystemHTTPProxy() {
        List<Proxy> proxyList;
        try {
            proxyList = ProxySelector.getDefault().select(new URI("http://foo/bar"));
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to get system proxy", e);
        }

        for (Proxy proxy: proxyList) {
            if (proxy.type() == Proxy.Type.HTTP) {
                return proxy;
            }
        }
        return null;
    }

    /**
     * Check whether the input string is potentially json or xml, and return pretty printed string accordingly.
     * If the input string is not a well formed json or xml, return it as is.
     * If the input is null, return null.
     * @param input
     * @return
     * @throws TransformerException
     */
    public static String prettyPrintJSONOrXML(String input) throws TransformerException, IOException, XPathExpressionException {
        if (input == null) {
            return null;
        }

        String trimmedInput = input.trim();
        if (trimmedInput.toUpperCase().startsWith("<!DOCTYPE HTML")) {
            //  not formatting html with DOCTYPE for now, as
            //    1. it could cause https://stackoverflow.com/questions/39189174/dom-parser-freezes-with-an-html-having-a-doctype-declaration
            //    2. if using DocumentBuilderFactory.setAttribute("http://apache.org/xml/features/nonvalidating/load-external-dtd", false),
            //        the freeze can be avoided, but the returned pretty printed html from XMLUtils.prettyPrintXML method is missing the DOCTYPE declaration.
            //        This is possibly due to https://stackoverflow.com/questions/6637076/parsing-xml-with-dom-doctype-gets-erased
            //  an example declaration: <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
            return input;
        } else if (trimmedInput.startsWith("<") && trimmedInput.endsWith(">")) {     //  potentially xml (impossible to be json)
            return XMLUtils.prettyPrintXML(input);
        } else if (trimmedInput.startsWith("[") || trimmedInput.startsWith("{")) {   //  potentially json array/object (impossible to be xml)
            //  notice that string "111 222 333" will be parsed by Jackson as Integer 111, so only pretty print potential json array/object here.
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);  //  haven't found a way, without custom code, to pretty print JSON with duplicate keys (which is invalid JSON)
            Object jsonObject;
            try {
                jsonObject = objectMapper.readValue(input, Object.class);
            } catch (Exception e) {
                //  the input string is not well formed JSON
                return input;
            }
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
        } else {
            return input;
        }
    }

    public static void addMixInsForWireMock(ObjectMapper objectMapper) {
        objectMapper.addMixIn(StubMapping.class, StubMappingMixIn.class);
        objectMapper.addMixIn(RequestPattern.class, RequestPatternMixIn.class);
        objectMapper.addMixIn(StringValuePattern.class, StringValuePatternMixIn.class);
        objectMapper.addMixIn(ResponseDefinition.class, ResponseDefinitionMixIn.class);
        objectMapper.addMixIn(ContentPattern.class, ContentPatternMixIn.class);
        objectMapper.addMixIn(LoggedResponse.class, LoggedResponseMixIn.class);
        objectMapper.addMixIn(ServeEvent.class, ServeEventMixIn.class);
        objectMapper.addMixIn(LoggedRequest.class, LoggedRequestMixIn.class);
    }

    /**
     * Create (clone) a new instance out of the stub spec, with UUID generated for the instance.
     * The instance also has the apiTestBaseId as metadata.
     * The spec is not changed.
     * @return
     */
    public static StubMapping createStubInstance(long apiTestBaseId, short apiTestBaseNumber, StubMapping spec) {
        StubMapping stubInstance = StubMapping.buildFrom(StubMapping.buildJsonStringFor(spec));
        stubInstance.setMetadata(metadata()
                .attr(WIREMOCK_STUB_METADATA_ATTR_NAME_API_TEST_BASE_ID, apiTestBaseId)
                .attr(WIREMOCK_STUB_METADATA_ATTR_NAME_API_TEST_BASE_NUMBER, apiTestBaseNumber)
                .build());
        stubInstance.setDirty(false);
        return stubInstance;
    }

    /**
     * By default, unmatched WireMock stub request (ServeEvent) does not have the actual response headers or response body.
     * This method update the unmatched serveEvent obtained from the WireMockServer by changing its response headers and body to the actual values.
     * @param serveEvent
     * @return the input serveEvent if it was matched;
     *         a new ServeEvent object with all fields same as the input serveEvent, except for the response headers and body, if it was unmatched.
     */
    public static ServeEvent updateUnmatchedStubRequest(ServeEvent serveEvent, WireMockServer wireMockServer) {
        if (serveEvent.getWasMatched()) {
            return serveEvent;
        } else {
            PlainTextStubNotMatchedRenderer renderer = (PlainTextStubNotMatchedRenderer) wireMockServer.getOptions()
                    .getNotMatchedRenderer();
            ResponseDefinition responseDefinition = renderer.render(wireMockServer, serveEvent.getRequest());
            LoggedResponse response = serveEvent.getResponse();
            com.github.tomakehurst.wiremock.http.HttpHeaders updatedHeaders = responseDefinition.getHeaders();
            String updatedBody = responseDefinition.getBody().substring(2);  //  remove the leading \r\n
            LoggedResponse updatedResponse = new LoggedResponse(response.getStatus(), updatedHeaders,
                    Encoding.encodeBase64(updatedBody.getBytes()), response.getFault(), null);
            ServeEvent updatedServeEvent = new ServeEvent(serveEvent.getId(), serveEvent.getRequest(),
                    serveEvent.getStubMapping(), serveEvent.getResponseDefinition(), updatedResponse,
                    serveEvent.getWasMatched(), serveEvent.getTiming());
            return updatedServeEvent;
        }
    }

    public static void substituteRequestBodyMainPatternValue(List<HTTPStubMapping> httpStubMappings) {
        for (HTTPStubMapping httpStubMapping: httpStubMappings) {
            StubMapping spec = httpStubMapping.getSpec();
            List<ContentPattern<?>> requestBodyPatterns = spec.getRequest().getBodyPatterns();
            if (requestBodyPatterns != null) {
                for (int i = 0; i < requestBodyPatterns.size(); i++) {
                    ContentPattern requestBodyPattern = requestBodyPatterns.get(i);
                    if (requestBodyPattern instanceof EqualToXmlPattern) {
                        EqualToXmlPattern equalToXmlPattern = (EqualToXmlPattern) requestBodyPattern;
                        requestBodyPatterns.set(i, new EqualToXmlPattern(
                                httpStubMapping.getRequestBodyMainPatternValue(),
                                equalToXmlPattern.isEnablePlaceholders(),
                                equalToXmlPattern.getPlaceholderOpeningDelimiterRegex(),
                                equalToXmlPattern.getPlaceholderClosingDelimiterRegex(),
                                null));
                        break;
                    } else if (requestBodyPattern instanceof EqualToJsonPattern) {
                        EqualToJsonPattern equalToJsonPattern = (EqualToJsonPattern) requestBodyPattern;
                        requestBodyPatterns.set(i, new EqualToJsonPattern(
                                httpStubMapping.getRequestBodyMainPatternValue(),
                                equalToJsonPattern.isIgnoreArrayOrder(), equalToJsonPattern.isIgnoreExtraElements()));
                        break;
                    }
                }
            }
        }
    }

    public static void validateMQRFH2FolderStringAndSetFolderName(MQRFH2Folder folder) {
        //  validate folder string is well formed XML
        Document doc;
        try {
            doc = XMLUtils.xmlStringToDOM(folder.getString());
        } catch (Exception e) {
            throw new RuntimeException("Folder string is not a valid XML. " + folder.getString(), e);
        }

        //  update folder name to be the XML root element name
        folder.setName(doc.getDocumentElement().getTagName());
    }

    public static String base64EncodeByteArray(byte[] bytes) {
        return bytes == null ? null : Base64.encodeBase64String(bytes);
    }
}
