/*
Copyright 2020 Spunk Inc.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.splunk.streaming;

import com.splunk.streaming.data.Tuple;
import com.splunk.streaming.flink.streams.core.StreamingFunction;
import com.splunk.streaming.flink.streams.core.metrics.CustomMetrics;
import com.splunk.streaming.flink.streams.planner.PlannerContext;
import com.splunk.streaming.upl3.core.ApiVersion;
import com.splunk.streaming.upl3.core.FunctionCallContext;
import com.splunk.streaming.upl3.core.RegistryVersion;
import com.splunk.streaming.upl3.core.ReturnTypeAdviser;
import com.splunk.streaming.upl3.core.Types;
import com.splunk.streaming.upl3.language.Category;
import com.splunk.streaming.upl3.language.Type;
import com.splunk.streaming.upl3.language.TypeRegistry;
import com.splunk.streaming.upl3.node.FunctionCall;
import com.splunk.streaming.upl3.node.NamedNode;
import com.splunk.streaming.upl3.node.StringValue;
import com.splunk.streaming.upl3.plugins.Attributes;
import com.splunk.streaming.upl3.plugins.Categories;
import com.splunk.streaming.upl3.plugins.Lifecycles;
import com.splunk.streaming.upl3.type.CollectionType;
import com.splunk.streaming.upl3.type.ExpressionType;
import com.splunk.streaming.upl3.type.FunctionType;
import com.splunk.streaming.upl3.type.IntegerType;
import com.splunk.streaming.upl3.type.MapType;
import com.splunk.streaming.upl3.type.RecordType;
import com.splunk.streaming.upl3.type.SchemaType;
import com.splunk.streaming.upl3.type.StringType;
import com.splunk.streaming.upl3.type.TypeVariable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

@RegistryVersion(minMajorVersion = 3, minStabilityLevel = ApiVersion.StabilityLevel.BETA, minIterationNumber = 1)
public class PhantomPlaybookFunction implements StreamingFunction, ReturnTypeAdviser {
  private static final Logger logger = LoggerFactory.getLogger(PhantomPlaybookFunction.class);
  private static final long serialVersionUID = 1L;
  private static final String NAME = "phantom_playbook";
  private static final String UI_NAME = "Trigger Phantom Playbook";
  private static final String UI_DESCRIPTION = "Trigger a Playbook in Phantom";

  private static final String INPUT_ARG = "input";
  private static final String AUTH_TOKEN_ARG = "auth_token";
  private static final String PHANTOM_HOST_ARG = "phantom_host";
  private static final String PLAYBOOK_ID_ARG = "playbook_id";
  private static final String CONTAINER_ID_ARG = "container_id";
  private static final String SCOPE_ARG = "scope";


  @Override
  public FunctionType getFunctionType() {
    CollectionType inStream = new CollectionType(new RecordType(new TypeVariable("R")));
    CollectionType outStream = new CollectionType(new RecordType(new TypeVariable("S")));

    return FunctionType.newStreamingFunctionBuilder(this)
            .addArgument(INPUT_ARG, inStream)
            // connection info
            .addArgument(AUTH_TOKEN_ARG, StringType.INSTANCE)
            .addArgument(PHANTOM_HOST_ARG, StringType.INSTANCE)
            // required parameters
            .addArgument(PLAYBOOK_ID_ARG, IntegerType.INSTANCE)
            .addArgument(CONTAINER_ID_ARG, IntegerType.INSTANCE)
            .addArgument(SCOPE_ARG, StringType.INSTANCE, true, new StringValue("all"))
            .returns(outStream)
            .build();
  }

  @Override
  public List<Category> getCategories() {
    return ImmutableList.of(Categories.FUNCTION.getCategory());
  }

  @Override
  public Map<String, Object> getAttributes() {
    Map<String, Object> attributes = Maps.newHashMap();
    attributes.put(Attributes.NAME.toString(), UI_NAME);
    attributes.put(Attributes.DESCRIPTION.toString(), UI_DESCRIPTION);
    return attributes;
  }

  @Override
  public String getName() {
    return NAME;
  }

  // Create Flink plan
  @Override
  public DataStream<Tuple> plan(FunctionCall functionCall, PlannerContext context, StreamExecutionEnvironment streamExecutionEnvironment) {
    DataStream<Tuple> input = context.getArgument(INPUT_ARG);
    String authToken = context.getConstantArgument(AUTH_TOKEN_ARG);
    String phantomHost = context.getConstantArgument(PHANTOM_HOST_ARG);
    int playbookId = context.getConstantArgument(PLAYBOOK_ID_ARG);
    int containerId = context.getConstantArgument(CONTAINER_ID_ARG);
    String scope = context.getConstantArgument(SCOPE_ARG);

    CustomMetrics customMetrics = CustomMetrics.newBuilder()
            .setTenantId(context.getPlannerConfiguration().getTenantId())
            .setPipelineId(context.getPlannerConfiguration().getPipelineId())
            .setNodeId(functionCall.getId())
            .setFunctionName(NAME)
            .build();

    RunPhantomPlaybook phantomPlaybookFunction = new RunPhantomPlaybook(authToken, phantomHost, playbookId, containerId, scope);
    String name = String.format("%s(action-name: %s)", NAME, playbookId);
    return input.map(phantomPlaybookFunction)
            .name(name)
            .uid(createUid(functionCall));
  }

  @Override
  public Type getReturnType(FunctionCall functionCall, TypeRegistry typeRegistry, FunctionCallContext context) {
    List<NamedNode> args = functionCall.getCanonicalizedArguments();
    SchemaType inputSchema = Types.getResolvedSchema(functionCall, args.get(0));
    SchemaType.Builder schemaBuilder = SchemaType.newBuilder(true);

    // Build the output schema
    // For now, the output schema is the same as the input schema
    for (Map.Entry<String, Type> entry : inputSchema.getFields().entrySet()) {
      schemaBuilder.addField(entry.getKey(), entry.getValue());
    }

    SchemaType outSchema = schemaBuilder.build();
    return new CollectionType(new RecordType(outSchema));
  }

  private static class RunPhantomPlaybook extends RichMapFunction<Tuple, Tuple> {

    private static final long serialVersionUID = 1L;
    private final String authToken;
    private final String phantomHost;
    private final int playbookId;
    private final int containerId;
    private final String scope;

    private RunPhantomPlaybook(String authToken, String phantomHost, int playbookId, int containerId, String scope) {
      this.authToken = authToken;
      this.phantomHost = phantomHost;
      this.playbookId = playbookId;
      this.containerId = containerId;
      this.scope=scope;

    }

    // This function is called for every record that the function processes
    @Override
    public Tuple map(Tuple tuple) throws Exception {
      String[] action_result;
      action_result = PostPlaybookRun(phantomHost, authToken, playbookId, containerId,scope);
      //if playbook run returns HTTP 200 - capture the results
      /*
      if (action_result[0].equals("200")) {
        //Capture Run ID
        String id = ExtractID(action_result[1]);

        //Capture Action Results
        GetActionResults(id, authToken, phantomHost);
      }
      */
      return tuple;
    }

    private String ExtractID(String response) throws Exception {
      String id= "";
      Pattern p = Pattern.compile("playbook_run_id[^\\d]+([0-9]+)");
      Matcher m = p.matcher(response);
      if (m.find()) {
//        System.out.println(m.group(1));
        id = m.group(1);
      } else {
        logger.debug("RUN ID not found");
      }
      return id;
    }

    private SSLSocketFactory buildSocketFactory() throws Exception {
      TrustManager[] trustAllCerts = new TrustManager[] {
              new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] x509Certificates,
                                               String s) throws CertificateException {}
                @Override
                public void checkServerTrusted(X509Certificate[] x509Certificates,
                                               String s) throws CertificateException {}
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                  return new X509Certificate[0];
                }
              }
      };
      SSLContext sc = SSLContext.getInstance("SSL");
      sc.init(null, trustAllCerts, new java.security.SecureRandom());
      return sc.getSocketFactory();
    }

    private String[] PostPlaybookRun(String phantomHost, String authToken, int playbookId, int containerId, String scope) throws Exception {

      URL urlForPostRequest = new URL("https://" + phantomHost + "/rest/playbook_run");
      String response_action = "";
      String[] ans_returned = new String[2];

      String readLine = null;
      HttpsURLConnection connection = (HttpsURLConnection) urlForPostRequest.openConnection();
      connection.setSSLSocketFactory(buildSocketFactory());
      connection.setRequestMethod("POST");
      connection.setRequestProperty("ph-auth-token", authToken);
      connection.setRequestProperty("Accept-Charset", "UTF-8");
      connection.setHostnameVerifier(new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession sslSession) {
          return true;
        }
      });
      final String POST_PARAMS = "{\"container_id\":"+ containerId + ",\"playbook_id\":" + playbookId + ",\"scope\":\"" + scope + "\", \"run\":true}";
      connection.setDoOutput(true);
      OutputStream os = connection.getOutputStream();
      os.write(POST_PARAMS.getBytes());
      os.flush();
      os.close();


      int responseCode = connection.getResponseCode();
      logger.debug("Post action run response code: {}", responseCode);

      ans_returned[0] =Integer.toString(responseCode);
      if (responseCode >= 400) {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(connection.getErrorStream()));
        StringBuffer response = new StringBuffer();
        while ((readLine = in .readLine()) != null) {
          response.append(readLine);
        } in .close();
        // print result
        logger.error("Failed to send action request: {}", response.toString());
      }
      if (responseCode == HttpsURLConnection.HTTP_OK) {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(connection.getInputStream()));
        StringBuffer response = new StringBuffer();
        while ((readLine = in .readLine()) != null) {
          response.append(readLine);
        } in.close();
        // print result
        response_action=response.toString();
        ans_returned[1]=response_action;

      } else {
        logger.error("Failed to get send action request: {} POST params {}", responseCode, POST_PARAMS);
      }
      return ans_returned;

    }

    private String GetActionResults(String id, String authToken, String phantomHost) throws Exception {
      URL urlForGetRequest = new URL("https://" + phantomHost + "/rest/action_run/" + id + "/app_runs");
      String response_action = "";
      String readLine = null;
      HttpsURLConnection connection = (HttpsURLConnection) urlForGetRequest.openConnection();
      connection.setSSLSocketFactory(buildSocketFactory());
      connection.setDoOutput(true);
      connection.setRequestProperty("ph-auth-token", authToken);
      connection.setRequestMethod("GET");
      connection.setRequestProperty("Accept-Charset", "UTF-8");

      connection.setHostnameVerifier(new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession sslSession) {
          return true;
        }
      });

      //connection.connect();
      int responseCode = connection.getResponseCode();
      logger.debug("Get action results response code: {}", responseCode);

      if (responseCode >= 400) {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(connection.getErrorStream()));
        StringBuffer response = new StringBuffer();
        while ((readLine = in .readLine()) != null) {
          response.append(readLine);
        } in.close();
        // print result
        logger.error("Failed to get Action run results: {}", response.toString());
      }
      if (responseCode == HttpsURLConnection.HTTP_OK) {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(connection.getInputStream()));
        StringBuffer response = new StringBuffer();
        while ((readLine = in .readLine()) != null) {
          response.append(readLine);
        }
        in.close();
        // print result
        response_action=response.toString();
      } else {
        logger.error("Failed to get Action run results: {}", responseCode);
      }
      return response_action;
    }
  }
}
