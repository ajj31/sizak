/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.metron.stellar.dsl.functions;

import com.google.common.collect.ImmutableMap;
import org.adrianwalker.multilinestring.Multiline;
import org.apache.commons.io.FileUtils;
import org.apache.metron.stellar.dsl.Context;
import org.apache.metron.stellar.dsl.ParseException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.mockserver.client.server.MockServerClient;
import org.mockserver.junit.MockServerRule;
import org.mockserver.junit.ProxyRule;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.apache.metron.stellar.common.utils.StellarProcessorUtils.run;
import static org.apache.metron.stellar.dsl.functions.RestConfig.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class RestFunctionsIntegrationTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Rule
  public TemporaryFolder tempDir = new TemporaryFolder();

  @Rule
  public MockServerRule mockServerRule = new MockServerRule(this);

  @Rule
  public ProxyRule proxyRule = new ProxyRule(1080, this);

  private MockServerClient mockServerClient;
  private String baseUri;
  private String getUri;
  private String emptyGetUri;
  private String postUri;
  private String emptyPostUri;
  private Context context;

  private File basicAuthPasswordFile;
  private String basicAuthPassword = "password";
  private File proxyBasicAuthPasswordFile;
  private String proxyAuthPassword = "proxyPassword";

  @Before
  public void setup() throws Exception {
    context = new Context.Builder()
            .with(Context.Capabilities.GLOBAL_CONFIG, HashMap::new)
            .build();

    // Store the passwords in the local file system
    basicAuthPasswordFile = tempDir.newFile("basicAuth.txt");
    FileUtils.writeStringToFile(basicAuthPasswordFile, basicAuthPassword, StandardCharsets.UTF_8);
    proxyBasicAuthPasswordFile = tempDir.newFile("proxyBasicAuth.txt");
    FileUtils.writeStringToFile(proxyBasicAuthPasswordFile, proxyAuthPassword, StandardCharsets.UTF_8);

    // By default, the mock server expects a GET request with the path set to /get
    baseUri = String.format("http://localhost:%d", mockServerRule.getPort());
    getUri = baseUri + "/get";
    emptyGetUri = baseUri + "/get/empty";
    postUri = baseUri + "/post";
    emptyPostUri = baseUri + "/post/empty";
    mockServerClient.when(
            request()
                    .withMethod("GET")
                    .withPath("/get"))
            .respond(response()
                    .withBody("{\"get\":\"success\"}"));
    mockServerClient.when(
            request()
                    .withMethod("GET")
                    .withPath("/get/empty"))
            .respond(response()
                    .withStatusCode(404));

    mockServerClient.when(
            request()
                    .withMethod("POST")
                    .withPath("/post")
                    .withBody("{\"key\":\"value\"}"))
            .respond(response()
                    .withBody("{\"post\":\"success\"}"));
    mockServerClient.when(
            request()
                    .withMethod("POST")
                    .withPath("/post/empty"))
            .respond(response()
                    .withStatusCode(404));
  }

  /**
   * The REST_GET function should perform a get request and parse the results.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void restGetShouldSucceed() throws Exception {
    Map<String, Object> actual = (Map<String, Object>) run(String.format("REST_GET('%s')", getUri), context);

    assertEquals(1, actual.size());
    assertEquals("success", actual.get("get"));
  }

  /**
   * The REST_GET function should perform a get request and parse the results.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void restGetShouldSucceedWithQueryParameters() throws Exception {
    mockServerClient.when(
            request()
                    .withMethod("GET")
                    .withPath("/get/with/query/parameters")
                    .withQueryStringParameter("key", "value"))
            .respond(response()
                    .withBody("{\"get.with.query.parameters\":\"success\"}"));

    Map<String, Object> variables = ImmutableMap.of("queryParameters", ImmutableMap.of("key", "value"));
    Map<String, Object> actual = (Map<String, Object>) run(String.format("REST_GET('%s', {}, queryParameters)",
            baseUri + "/get/with/query/parameters"), variables, context);

    assertEquals(1, actual.size());
    assertEquals("success", actual.get("get.with.query.parameters"));
  }

  /**
   * The REST_GET function should perform a get request using a proxy and parse the results.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void restGetShouldSucceedWithProxy() {
    mockServerClient.when(
            request()
                    .withMethod("GET")
                    .withPath("/get"))
            .respond(response()
                    .withBody("{\"proxyGet\":\"success\"}"));

    context.addCapability(Context.Capabilities.GLOBAL_CONFIG, () -> new HashMap<String, Object>() {{
      put(PROXY_HOST, "localhost");
      put(PROXY_PORT, proxyRule.getHttpPort());
    }});

    Map<String, Object> actual = (Map<String, Object>) run(String.format("REST_GET('%s')", getUri), context);

    assertEquals(1, actual.size());
    assertEquals("success", actual.get("proxyGet"));
  }

  /**
   * The REST_GET function should handle an error status code and return null by default.
   */
  @Test
  public void restGetShouldHandleErrorStatusCode() {
    mockServerClient.when(
            request()
                    .withMethod("GET")
                    .withPath("/get"))
            .respond(response()
                    .withStatusCode(403));

    assertNull(run(String.format("REST_GET('%s')", getUri), context));
  }

  /**
   * {
   *   "response.codes.allowed": [200,404],
   *   "empty.content.override": "function config override"
   * }
   */
  @Multiline
  private String emptyContentOverride;

  /**
   * The REST_GET function should return the empty content override setting when status is allowed and content is empty.
   */
  @Test
  public void restGetShouldReturnEmptyContentOverride() {
    assertEquals("function config override", run(String.format("REST_GET('%s', %s)", emptyGetUri, emptyContentOverride), context));
  }

  /**
   * {
   *   "error.value.override": "error message"
   * }
   */
  @Multiline
  private String errorValueOverride;

  /**
   * The REST_GET function should return the error value override setting on error.
   */
  @Test
  public void restGetShouldReturnErrorValueOverride() {
    mockServerClient.when(
            request()
                    .withMethod("GET")
                    .withPath("/get"))
            .respond(response()
                    .withStatusCode(500));

    Object result = run(String.format("REST_GET('%s', %s)", getUri, errorValueOverride), context);
    assertEquals("error message" , result);
  }

  /**
   * The REST_GET function should timeout and return null.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void restGetShouldTimeout() {
    String uri = String.format("http://localhost:%d/get", mockServerRule.getPort());

    mockServerClient.when(
            request()
                    .withMethod("GET")
                    .withPath("/get"))
            .respond(response()
                    .withBody("{\"get\":\"success\"}"));

    Map<String, Object> globalConfig = new HashMap<String, Object>() {{
      put(STELLAR_REST_SETTINGS, new HashMap<String, Object>() {{
        put(TIMEOUT, 1);
      }});
    }};

    context.addCapability(Context.Capabilities.GLOBAL_CONFIG, () -> globalConfig);

    Map<String, Object> actual = (Map<String, Object>) run(String.format("REST_GET('%s')", uri), context);
    assertNull(actual);
  }

  /**
   * {
   * "timeout": 1
   * }
   */
  @Multiline
  private String timeoutConfig;

  /**
   * The REST_GET function should honor the function supplied timeout setting.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void restGetShouldTimeoutWithSuppliedTimeout() {
    String expression = String.format("REST_GET('%s', %s)", getUri, timeoutConfig);
    Map<String, Object> actual = (Map<String, Object>) run(expression, context);
    assertNull(actual);
  }

  /**
   * The REST_GET function should throw an exception on a malformed uri.
   * @throws IllegalArgumentException
   * @throws IOException
   */
  @Test
  public void restGetShouldHandleURISyntaxException() throws IllegalArgumentException, IOException {
    thrown.expect(ParseException.class);
    thrown.expectMessage("Unable to parse REST_GET('some invalid uri'): Unable to parse: REST_GET('some invalid uri') due to: Illegal character in path at index 4: some invalid uri");

    run("REST_GET('some invalid uri')", context);
  }



  /**
   * The REST_GET function should throw an exception when the required uri parameter is missing.
   */
  @Test
  public void restGetShouldThrownExceptionOnMissingParameter() {
    thrown.expect(ParseException.class);
    thrown.expectMessage("Unable to parse REST_GET(): Unable to parse: REST_GET() due to: Expected at least 1 argument(s), found 0");

    run("REST_GET()", context);
  }

  /**
   * Global config Stellar REST settings should take precedence over defaults in the REST_GET function.
   */
  @Test
  public void restGetShouldUseGlobalConfig() {
    Map<String, Object> globalConfig = new HashMap<String, Object>() {{
      put(STELLAR_REST_SETTINGS, new HashMap<String, Object>() {{
        put(RESPONSE_CODES_ALLOWED, Arrays.asList(200, 404));
        put(EMPTY_CONTENT_OVERRIDE, "global config override");
      }});
    }};
    context.addCapability(Context.Capabilities.GLOBAL_CONFIG, () -> globalConfig);

    assertEquals("global config override", run(String.format("REST_GET('%s')", emptyGetUri), context));
  }

  /**
   * Global config Stellar REST GET settings should take precedence over general Stellar REST settings in the REST_GET function.
   */
  @Test
  public void restGetShouldUseGetConfig() {
    Map<String, Object> globalConfig = new HashMap<String, Object>() {{
      put(STELLAR_REST_SETTINGS, new HashMap<String, Object>() {{
        put(RESPONSE_CODES_ALLOWED, Arrays.asList(200, 404));
        put(EMPTY_CONTENT_OVERRIDE, "global config override");
      }});
      put(STELLAR_REST_GET_SETTINGS, new HashMap<String, Object>() {{
        put(EMPTY_CONTENT_OVERRIDE, "get config override");
      }});
    }};
    context.addCapability(Context.Capabilities.GLOBAL_CONFIG, () -> globalConfig);

    assertEquals("get config override", run(String.format("REST_GET('%s')", emptyGetUri), context));
  }

  /**
   * Settings passed into the function should take precedence over all other settings in the REST_GET function.
   */
  @Test
  public void restGetShouldUseFunctionConfig() {
    Map<String, Object> globalConfig = new HashMap<String, Object>() {{
      put(STELLAR_REST_SETTINGS, new HashMap<String, Object>() {{
        put(RESPONSE_CODES_ALLOWED, Arrays.asList(200, 404));
        put(EMPTY_CONTENT_OVERRIDE, "global config override");
      }});
      put(STELLAR_REST_GET_SETTINGS, new HashMap<String, Object>() {{
        put(EMPTY_CONTENT_OVERRIDE, "get config override");
      }});
    }};
    context.addCapability(Context.Capabilities.GLOBAL_CONFIG, () -> globalConfig);

    assertEquals("function config override", run(String.format("REST_GET('%s', %s)", emptyGetUri, emptyContentOverride), context));
  }

  /**
   * The REST_POST function should perform a get request and parse the results.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void restPostShouldSucceed() throws Exception {
    Map<String, Object> actual = (Map<String, Object>) run(String.format("REST_POST('%s', '{\"key\":\"value\"}')", postUri), context);

    assertEquals(1, actual.size());
    assertEquals("success", actual.get("post"));
  }

  /**
   * The REST_POST function should perform a get request and parse the results.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void restPostShouldSucceedWithQueryParameters() throws Exception {
    mockServerClient.when(
            request()
                    .withMethod("POST")
                    .withPath("/post/with/query/parameters")
                    .withQueryStringParameter("key", "value"))
            .respond(response()
                    .withBody("{\"post.with.query.parameters\":\"success\"}"));

    Map<String, Object> variables = ImmutableMap.of("queryParameters", ImmutableMap.of("key", "value"));
    Map<String, Object> actual = (Map<String, Object>) run(String.format("REST_POST('%s', {}, {}, queryParameters)",
            baseUri + "/post/with/query/parameters"), variables, context);

    assertEquals(1, actual.size());
    assertEquals("success", actual.get("post.with.query.parameters"));
  }

  /**
   * The REST_POST function should perform a get request and parse the results.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void restPostShouldSucceedWithStellarMap() throws Exception {
    Map<String, Object> variables = ImmutableMap.of("body", ImmutableMap.of("key", "value"));
    Map<String, Object> actual = (Map<String, Object>) run(String.format("REST_POST('%s', body)", postUri), variables, context);

    assertEquals(1, actual.size());
    assertEquals("success", actual.get("post"));
  }

  /**
   * The REST_POST function should throw an exception on a malformed uri.
   * @throws IllegalArgumentException
   * @throws IOException
   */
  @Test
  public void restPostShouldHandleURISyntaxException() throws IllegalArgumentException, IOException {
    thrown.expect(ParseException.class);
    thrown.expectMessage("Unable to parse REST_POST('some invalid uri', {}): Unable to parse: REST_POST('some invalid uri', {}) due to: Illegal character in path at index 4: some invalid uri");

    run("REST_POST('some invalid uri', {})", context);
  }

  /**
   * The REST_POST function should throw an exception when POST data is not well-formed JSON and 'enforce.json' is set to true.
   * @throws IllegalArgumentException
   * @throws IOException
   */
  @Test
  public void restPostShouldThrowExceptionOnMalformedJson() throws IllegalArgumentException, IOException {
    thrown.expect(ParseException.class);
    thrown.expectMessage(String.format("Unable to parse: REST_POST('%s', 'malformed json') due to: POST data 'malformed json' must be properly formatted JSON.  " +
            "Set the 'enforce.json' property to false to disable this check.", postUri));

    run(String.format("REST_POST('%s', 'malformed json')", postUri), context);
  }

  /**
   * Global config Stellar REST settings should take precedence over defaults in the REST_POST function.
   */
  @Test
  public void restPostShouldUseGlobalConfig() {
    Map<String, Object> globalConfig = new HashMap<String, Object>() {{
      put(STELLAR_REST_SETTINGS, new HashMap<String, Object>() {{
        put(RESPONSE_CODES_ALLOWED, Arrays.asList(200, 404));
        put(EMPTY_CONTENT_OVERRIDE, "global config override");
      }});
    }};
    context.addCapability(Context.Capabilities.GLOBAL_CONFIG, () -> globalConfig);

    assertEquals("global config override", run(String.format("REST_POST('%s', {})", emptyGetUri), context));
  }

  /**
   * Global config Stellar REST POST settings should take precedence over general Stellar REST settings in the REST_POST function.
   */
  @Test
  public void restPostShouldUseGetConfig() {
    Map<String, Object> globalConfig = new HashMap<String, Object>() {{
      put(STELLAR_REST_SETTINGS, new HashMap<String, Object>() {{
        put(RESPONSE_CODES_ALLOWED, Arrays.asList(200, 404));
        put(EMPTY_CONTENT_OVERRIDE, "global config override");
      }});
      put(STELLAR_REST_POST_SETTINGS, new HashMap<String, Object>() {{
        put(EMPTY_CONTENT_OVERRIDE, "post config override");
      }});
    }};
    context.addCapability(Context.Capabilities.GLOBAL_CONFIG, () -> globalConfig);

    assertEquals("post config override", run(String.format("REST_POST('%s', {})", emptyGetUri), context));
  }

  /**
   * Settings passed into the function should take precedence over all other settings in the REST_POST function.
   */
  @Test
  public void restPostShouldUseFunctionConfig() {
    Map<String, Object> globalConfig = new HashMap<String, Object>() {{
      put(STELLAR_REST_SETTINGS, new HashMap<String, Object>() {{
        put(RESPONSE_CODES_ALLOWED, Arrays.asList(200, 404));
        put(EMPTY_CONTENT_OVERRIDE, "global config override");
      }});
      put(STELLAR_REST_POST_SETTINGS, new HashMap<String, Object>() {{
        put(EMPTY_CONTENT_OVERRIDE, "post config override");
      }});
    }};
    context.addCapability(Context.Capabilities.GLOBAL_CONFIG, () -> globalConfig);

    assertEquals("function config override", run(String.format("REST_POST('%s', {}, %s)", emptyGetUri, emptyContentOverride), context));
  }

}
