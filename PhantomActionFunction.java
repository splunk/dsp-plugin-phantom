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

import com.splunk.streaming.data.Record;
import com.splunk.streaming.data.RecordDescriptor;
import com.splunk.streaming.data.Tuple;
import com.splunk.streaming.flink.streams.core.StreamingFunction;
import com.splunk.streaming.flink.streams.core.metrics.CustomMetrics;
import com.splunk.streaming.flink.streams.planner.PlannerContext;
import com.splunk.streaming.flink.streams.planner.ScalarFunctionWrapper;
import com.splunk.streaming.flink.streams.serialization.SerializableRecordDescriptor;
import com.splunk.streaming.upl3.core.ApiVersion;
import com.splunk.streaming.upl3.core.FunctionCallContext;
import com.splunk.streaming.upl3.core.RegistryVersion;
import com.splunk.streaming.upl3.core.ReturnTypeAdviser;
import com.splunk.streaming.upl3.core.Types;
import com.splunk.streaming.upl3.language.Category;
import com.splunk.streaming.upl3.language.Type;
import com.splunk.streaming.upl3.language.TypeRegistry;
import com.splunk.streaming.upl3.node.FunctionCall;
import com.splunk.streaming.upl3.node.IntegerValue;
import com.splunk.streaming.upl3.node.NamedNode;
import com.splunk.streaming.upl3.node.StringValue;
import com.splunk.streaming.upl3.plugins.Attributes;
import com.splunk.streaming.upl3.plugins.Categories;
import com.splunk.streaming.upl3.type.CollectionType;
import com.splunk.streaming.upl3.type.ExpressionType;
import com.splunk.streaming.upl3.type.FunctionType;
import com.splunk.streaming.upl3.type.IntegerType;
import com.splunk.streaming.upl3.type.MapType;
import com.splunk.streaming.upl3.type.RecordType;
import com.splunk.streaming.upl3.type.SchemaType;
import com.splunk.streaming.upl3.type.StringType;
import com.splunk.streaming.upl3.type.TypeVariable;
import com.splunk.streaming.util.TypeUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Serializable;
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
public class PhantomActionFunction implements StreamingFunction, ReturnTypeAdviser {
  private static final Logger logger = LoggerFactory.getLogger(PhantomActionFunction.class);
  private static final long serialVersionUID = 1L;
  private static final String NAME = "phantom_action";
  private static final String UI_NAME = "Trigger Phantom Action";
  private static final String UI_DESCRIPTION = "Trigger an action in Phantom";

  private static final String INPUT_ARG = "input";
  private static final String AUTH_TOKEN_ARG = "auth_token";
  private static final String PHANTOM_HOST_ARG = "phantom_host";
  private static final String ACTION_NAME_ARG = "action_name";
  private static final String CONTAINER_ID_ARG = "container_id";
  private static final String ASSET_ARG = "asset";
  private static final String PARAMETERS_ARG = "parameters";
  private static final String APP_ID_ARG = "app_id";
  private static final String ACTION_TYPE_ARG = "action_type";
  private static final String MAX_ATTEMPT_ARG = "max_attempt";
  private static final String TIMEOUT_ARG = "timeout";
  private static final String PHANTOM_ACTION_RESULT_FIELD_NAME = "phantom_action_result";

  @Override
  public FunctionType getFunctionType() {
    CollectionType inStream = new CollectionType(new RecordType(new TypeVariable("R")));
    CollectionType outStream = new CollectionType(new RecordType(new TypeVariable("S")));

    return FunctionType.newStreamingFunctionBuilder(this)
            .addArgument(INPUT_ARG, inStream)
            // connection info
            .addArgument(AUTH_TOKEN_ARG, StringType.INSTANCE)
            .addArgument(PHANTOM_HOST_ARG, StringType.INSTANCE)
            // other parameters
            // Note: I included all of these, but any can be made optional with default values
            .addArgument(ACTION_NAME_ARG, StringType.INSTANCE)
            .addArgument(CONTAINER_ID_ARG, IntegerType.INSTANCE)
            .addArgument(ASSET_ARG, StringType.INSTANCE)
            .addArgument(PARAMETERS_ARG, new MapType(StringType.INSTANCE, new ExpressionType(StringType.INSTANCE)))
            .addArgument(APP_ID_ARG, IntegerType.INSTANCE)
            .addArgument(ACTION_TYPE_ARG, StringType.INSTANCE)
            //optional arguments
            .addArgument(MAX_ATTEMPT_ARG, IntegerType.INSTANCE, true, new IntegerValue(10))
            .addArgument(TIMEOUT_ARG, IntegerType.INSTANCE, true, new IntegerValue(30))
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
  @SuppressWarnings("unchecked")
  public DataStream<Tuple> plan(FunctionCall functionCall, PlannerContext context, StreamExecutionEnvironment streamExecutionEnvironment) {
    RecordDescriptor outputDescriptor = context.getDescriptorForReturnType();
    DataStream<Tuple> input = context.getArgument(INPUT_ARG);
    String authToken = context.getConstantArgument(AUTH_TOKEN_ARG);
    String phantomHost = context.getConstantArgument(PHANTOM_HOST_ARG);
    String actionName = context.getConstantArgument(ACTION_NAME_ARG);
    int containerId = context.getConstantArgument(CONTAINER_ID_ARG);
    String asset = context.getConstantArgument(ASSET_ARG);

    Object params = context.getArgument(PARAMETERS_ARG);
    ParametersMap parameters;
    if (params instanceof Map) {
      parameters = new ParametersMap(null, TypeUtils.coerce(params));
    } else if (params instanceof ScalarFunctionWrapper) {
      parameters = new ParametersMap(((ScalarFunctionWrapper<Map<String, String>>) params).call(), null);
    } else {
      throw new IllegalArgumentException("Invalid map for argument: [parameters]");
    }
    int appId = context.getConstantArgument(APP_ID_ARG);
    String actionType = context.getConstantArgument(ACTION_TYPE_ARG);
    int maxAttempt = context.getConstantArgument(MAX_ATTEMPT_ARG);
    int timeout = context.getConstantArgument(TIMEOUT_ARG);

    CustomMetrics customMetrics = CustomMetrics.newBuilder()
            .setTenantId(context.getPlannerConfiguration().getTenantId())
            .setPipelineId(context.getPlannerConfiguration().getPipelineId())
            .setNodeId(functionCall.getId())
            .setFunctionName(NAME)
            .build();

    SerializableRecordDescriptor inputDescriptor = new SerializableRecordDescriptor(context.getDescriptorForArgument("input"));
    RunPhantomAction phantomActionFunction = new RunPhantomAction(outputDescriptor, inputDescriptor, authToken, phantomHost, actionName,
            containerId, asset, parameters, appId, actionType, maxAttempt, timeout);

    String name = String.format("%s(action-name: %s)", NAME, actionName);
    return input.map(phantomActionFunction)
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

    schemaBuilder.addField(PHANTOM_ACTION_RESULT_FIELD_NAME, StringType.INSTANCE);
    return new CollectionType(new RecordType(schemaBuilder.build()));
  }

  private static class RunPhantomAction extends RichMapFunction<Tuple, Tuple> {

    private static final long serialVersionUID = 1L;
    private final SerializableRecordDescriptor inputDescriptor;
    private final RecordDescriptor outputDescriptor;
    private final String authToken;
    private final String phantomHost;
    private final String actionName;
    private final int containerId;
    private final String asset;
    private final ParametersMap parameters;
    private final int appId;
    private final String actionType;
    private final int maxAttempt;
    private final int timeout;
    private final ObjectMapper mapper = new ObjectMapper();

    private RunPhantomAction(RecordDescriptor outputDescriptor, SerializableRecordDescriptor inputDescriptor, String authToken, String phantomHost, String actionName, int containerId,
                             String asset, ParametersMap parameters, int appId, String actionType, int maxAttempt, int timeout) {
      this.outputDescriptor = outputDescriptor;
      this.inputDescriptor = inputDescriptor;
      this.authToken = authToken;
      this.phantomHost = phantomHost;
      this.actionName = actionName;
      this.containerId = containerId;
      this.asset = asset;
      this.parameters = parameters;
      this.appId = appId;
      this.actionType = actionType;
      this.maxAttempt=maxAttempt;
      this.timeout=timeout;
    }

    // This function is called for every record that the function processes
    @Override
    public Tuple map(Tuple tuple) throws Exception {

      Record inputRecord = new Record(inputDescriptor.toRecordDescriptor(), tuple);
      Record outputRecord = new Record(outputDescriptor);
      Map<String, String> parametersMap = parameters.get(inputDescriptor, tuple);
      String[] action_result= new String[2];
      String[] post_result= new String[2];
      String full_response="";
      //action_result[1]="fail";
      post_result = PostActionRun(phantomHost, authToken, actionName, containerId, asset, appId, parametersMap, actionType);

      //if Action Run is successful
      if (post_result[0].equals("200")) {
        //post_result = PostActionRun(phantomHost, authToken, actionName, containerId, asset, appId, parametersMap, actionType);

        //Capture Run ID
        String id = ExtractID(post_result[1], "action_run_id[^\\d]+([0-9]+)");

        //Capture Phantom Action Result. Keep iterating until the action has been successfully been returned
        for(int i=1;i<=maxAttempt;i++) {

          action_result = GetActionResults(id, authToken, phantomHost);
          // if status of action run is success, then break
          if (action_result[0].equals("success")){
            full_response= GetAppRunResults(action_result[1], authToken, phantomHost);
            break;
          }
          //amount to sleep on each iteration is total timeout time divided by max attempts converted to ms
          int sleep_time = 1000*timeout/maxAttempt;
          Thread.sleep(sleep_time);
        }
        //if response is captured
        if (full_response!= "") {
          // copy all fields from the input record to the output record, except for the "phantom_action_result" field (if it exists)
          copyFields(inputRecord, outputRecord, PHANTOM_ACTION_RESULT_FIELD_NAME);
          // add the phantom action result field
          outputRecord.put(PHANTOM_ACTION_RESULT_FIELD_NAME, full_response);
        }
      }
      return outputRecord.getTuple();
    }

    private void copyFields(Record from, Record to, String excluded) {
      for (String fieldName : from.getFieldNames()) {
        if (!fieldName.equals(excluded)) {
          to.put(fieldName, from.get(fieldName));
        }
      }
    }

    private String ExtractID(String response, String pattern) throws Exception {
      String id = "";
      //Pattern p = Pattern.compile("action_run_id[^\\d]+([0-9]+),");
      Pattern p = Pattern.compile(pattern);
      Matcher m = p.matcher(response);
      if (m.find()) {
//        System.out.println(m.group(1));
        id = m.group(1);
      } else {
        logger.debug("ID not found");
      }
      return id;
    }

    private String getActionStatus(String response) throws Exception {
      String status = "";
      Pattern p = Pattern.compile("\"status\"[^\"]+\"([^\"]+)\",");
      Matcher m = p.matcher(response);
      if (m.find()) {
//        System.out.println(m.group(1));
        status = m.group(1);
      } else {
        logger.debug("Status not found");
      }
      return status;
    }

    private SSLSocketFactory buildSocketFactory() throws Exception {
      TrustManager[] trustAllCerts = new TrustManager[]{
              new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] x509Certificates,
                                               String s) throws CertificateException {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] x509Certificates,
                                               String s) throws CertificateException {
                }

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

    private String[] PostActionRun(String phantomHost, String authToken, String Action, int containerId, String asset, int appId, Map<String, String> parameters, String actionType) throws Exception {

      URL urlForPostRequest = new URL("https://" + phantomHost + "/rest/action_run");
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

      String params = mapper.writeValueAsString(parameters);

      final String POST_PARAMS = "{ \"action\": \"" + Action + "\", \"container_id\":" + containerId + ", \"name\": \"DSP\", \"targets\": [{ \"assets\": [\"" + asset + "\"], \"parameters\": [" + params + "], \"app_id\": " + appId + "}], \"type\": \"" + actionType + "\" }";

      connection.setDoOutput(true);
      OutputStream os = connection.getOutputStream();
      os.write(POST_PARAMS.getBytes());
      os.flush();
      os.close();


      int responseCode = connection.getResponseCode();
      logger.debug("Post action run response code: {}", responseCode);
      ans_returned[0] = Integer.toString(responseCode);
      if (responseCode >= 400) {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(connection.getErrorStream()));
        StringBuffer response = new StringBuffer();
        while ((readLine = in.readLine()) != null) {
          response.append(readLine);
        }
        in.close();
        // print result
        logger.error("Failed to send action request: {}", response.toString());
      }
      if (responseCode == HttpsURLConnection.HTTP_OK) {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(connection.getInputStream()));
        StringBuffer response = new StringBuffer();
        while ((readLine = in.readLine()) != null) {
          response.append(readLine);
        }
        in.close();
        // print result
        response_action = response.toString();
        ans_returned[1] = response_action;

      } else {
        logger.error("Failed to get send action request: {}", responseCode);
      }
      return ans_returned;

    }

    private String[] GetActionResults(String id, String authToken, String phantomHost) throws Exception {
      // System.setProperty("com.sun.jndi.ldap.object.disableEndpointIdentification", "true");
      URL urlForGetRequest = new URL("https://" + phantomHost + "/rest/action_run/" + id + "/app_runs");
      String response_action = "";
      String app_run_id="";
      String[] ans_returned = new String[2];

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
        while ((readLine = in.readLine()) != null) {
          response.append(readLine);
        }
        in.close();
        // print result
        logger.error("Failed to get Action run results: {}", response.toString());

      }
      if (responseCode == HttpsURLConnection.HTTP_OK) {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(connection.getInputStream()));
        StringBuffer response = new StringBuffer();
        while ((readLine = in.readLine()) != null) {
          response.append(readLine);
        }
        in.close();
        // return response
        response_action = response.toString();
        //capture app run id
        app_run_id = ExtractID(response_action, "\"id\"[^\\d]+([0-9]+),");
        ans_returned[1] = app_run_id;
        ans_returned[0]=getActionStatus(response_action);

      } else {
        logger.error("Failed to get Action run results: {}", responseCode);
      }
      return ans_returned;
    }

    private String GetAppRunResults(String appRunId, String authToken, String phantomHost) throws Exception {
      // System.setProperty("com.sun.jndi.ldap.object.disableEndpointIdentification", "true");
      URL urlForGetRequest = new URL("https://" + phantomHost + "/rest/app_run/" + appRunId);
      String response_app_run = "";
      //String[] ans_returned = new String[2];

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
        while ((readLine = in.readLine()) != null) {
          response.append(readLine);
        }
        in.close();
        // print result
        logger.error("Failed to get Action run results: {}", response.toString());

      }
      if (responseCode == HttpsURLConnection.HTTP_OK) {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(connection.getInputStream()));
        StringBuffer response = new StringBuffer();
        while ((readLine = in.readLine()) != null) {
          response.append(readLine);
        }
        in.close();
        // return response
        response_app_run = response.toString();
        //ans_returned[1] = response_action;
        //ans_returned[0]=getActionStatus(response_action);

      } else {
        logger.error("Failed to get App run results: {}", responseCode);
      }
      return response_app_run;
    }
  }

  private static class ParametersMap implements Serializable {
    private static final long serialVersionUID = 1L;
    private Map<String, String> mapLiteral;
    private Map<ScalarFunctionWrapper<String>, ScalarFunctionWrapper<String>> mapExpression;

    private ParametersMap(Map<String, String> mapLiteral, Map<ScalarFunctionWrapper<String>, ScalarFunctionWrapper<String>> mapExpression) {
      this.mapLiteral = mapLiteral;
      this.mapExpression = mapExpression;
      Preconditions.checkArgument(mapExpression != null ^ mapLiteral != null, "Only one of mapExpression and mapLiteral can be non-null");
    }

    private Map<String, String> get(SerializableRecordDescriptor inputDescriptor, Tuple tuple) {
      if (mapLiteral != null) {
        return mapLiteral;
      }

      Record inputRecord = new Record(inputDescriptor.toRecordDescriptor(), tuple);
      Map<String, String> parameters = Maps.newHashMap();
      for (Map.Entry<ScalarFunctionWrapper<String>, ScalarFunctionWrapper<String>> entry : mapExpression.entrySet()) {
        ScalarFunctionWrapper<String> keyExpr = entry.getKey();
        keyExpr.setRecord(inputRecord);
        String key = keyExpr.call();

        ScalarFunctionWrapper<String> valueExpr = entry.getValue();
        valueExpr.setRecord(inputRecord);
        String value = valueExpr.call();

        parameters.put(key, value);
      }
      return parameters;
    }
  }
}