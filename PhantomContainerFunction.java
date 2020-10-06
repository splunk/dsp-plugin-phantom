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


import com.splunk.streaming.data.RecordDescriptor;
import com.splunk.streaming.data.Record;
import com.splunk.streaming.flink.streams.planner.ScalarFunctionWrapper;
import com.splunk.streaming.flink.streams.serialization.SerializableRecordDescriptor;
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
import com.splunk.streaming.upl3.node.MapValue;
import com.splunk.streaming.upl3.node.NamedNode;
import com.splunk.streaming.upl3.node.StringValue;
import com.splunk.streaming.upl3.plugins.Attributes;
import com.splunk.streaming.upl3.plugins.Categories;
import com.splunk.streaming.upl3.plugins.Lifecycles;
import com.splunk.streaming.upl3.type.CollectionType;
import com.splunk.streaming.upl3.type.ExpressionType;
import com.splunk.streaming.upl3.type.FunctionType;

import com.splunk.streaming.upl3.type.MapType;
import com.splunk.streaming.upl3.type.RecordType;
import com.splunk.streaming.upl3.type.SchemaType;
import com.splunk.streaming.upl3.type.StringType;
import com.splunk.streaming.upl3.type.TypeVariable;
import com.splunk.streaming.upl3.type.ExpressionType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.splunk.streaming.util.TypeUtils;
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
import java.util.ArrayList;
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
import com.splunk.streaming.flink.streams.planner.ScalarFunctionWrapper;
import com.splunk.streaming.flink.streams.serialization.SerializableRecordDescriptor;


@RegistryVersion(minMajorVersion = 3, minStabilityLevel = ApiVersion.StabilityLevel.BETA, minIterationNumber = 1)
public class PhantomContainerFunction implements StreamingFunction, ReturnTypeAdviser {
  private static final Logger logger = LoggerFactory.getLogger(PhantomContainerFunction.class);
  private static final long serialVersionUID = 1L;
  private static final String NAME = "phantom_container";
  private static final String UI_NAME = "Trigger Phantom Case or Event";
  private static final String UI_DESCRIPTION = "Trigger an event or case in Phantom";

  private static final String INPUT_ARG = "input";
  private static final String AUTH_TOKEN_ARG = "auth_token";
  private static final String PHANTOM_HOST_ARG = "phantom_host";

  private static final String CONTAINER_NAME_ARG = "name";
  private static final String DESCRIPTION_ARG = "description";
  private static final String OWNER_ID_ARG = "owner_id";
  private static final String FIELDS_ARG = "fields";


  private static final String CEF_TYPES_ARG = "fields_type";
  private static final String LABEL_ARG = "label";
  private static final String SEVERITY_ARG = "severity";
  private static final String SENSITIVITY_ARG = "sensitivity";
  private static final String STATUS_ARG = "status";
  private static final String CONTAINER_TYPE_ARG = "type";
  private static final String PHANTOM_CONTAINER_RESULT_FIELD_NAME = "phantom_container_result";

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
            .addArgument(FIELDS_ARG, new MapType(StringType.INSTANCE, new ExpressionType(StringType.INSTANCE)))
            .addArgument(CONTAINER_NAME_ARG, StringType.INSTANCE)
            .addArgument(DESCRIPTION_ARG, StringType.INSTANCE)
            .addArgument(OWNER_ID_ARG, StringType.INSTANCE)
            //Optional parameters
            .addArgument(CEF_TYPES_ARG, new MapType(StringType.INSTANCE, new ExpressionType(StringType.INSTANCE)), true, new MapValue<>( new MapType(StringType.INSTANCE, new ExpressionType(StringType.INSTANCE)), Maps.newHashMap()))
            .addArgument(CONTAINER_TYPE_ARG, StringType.INSTANCE, true, new StringValue("default"))
            .addArgument(STATUS_ARG, StringType.INSTANCE, true, new StringValue("new"))
            .addArgument(SEVERITY_ARG, StringType.INSTANCE, true, new StringValue("medium"))
            .addArgument(SENSITIVITY_ARG, StringType.INSTANCE, true, new StringValue("amber"))
            .addArgument(LABEL_ARG, StringType.INSTANCE, true, new StringValue("events"))

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

    Object params = context.getArgument(FIELDS_ARG);
    PhantomContainerFunction.customFieldsMap customFields;
    if (params instanceof Map) {
      customFields = new PhantomContainerFunction.customFieldsMap(null, TypeUtils.coerce(params));
    } else if (params instanceof ScalarFunctionWrapper) {
      customFields = new PhantomContainerFunction.customFieldsMap(((ScalarFunctionWrapper<Map<String, String>>) params).call(), null);
    } else {
      throw new IllegalArgumentException("Invalid map for argument: [parameters]");
    }
    // Map<ScalarFunctionWrapper<String>, ScalarFunctionWrapper<String>> customFields = context.getArgument(CUSTOM_FIELDS_ARG);
    //Map<ScalarFunctionWrapper<String>, ScalarFunctionWrapper<String>> cefTypes = context.getArgument(CEF_TYPES_ARG);
    Map<String, String> cefTypes = context.getConstantArgument(CEF_TYPES_ARG);
    String description = context.getConstantArgument(DESCRIPTION_ARG);
    String label = context.getConstantArgument(LABEL_ARG);
    String ownerId = context.getConstantArgument(OWNER_ID_ARG);
    String containerName = context.getConstantArgument(CONTAINER_NAME_ARG);
    String severity = context.getConstantArgument(SEVERITY_ARG);
    String sensitivity = context.getConstantArgument(SENSITIVITY_ARG);
    String status = context.getConstantArgument(STATUS_ARG);
    String containerType = context.getConstantArgument(CONTAINER_TYPE_ARG);

    CustomMetrics customMetrics = CustomMetrics.newBuilder()
            .setTenantId(context.getPlannerConfiguration().getTenantId())
            .setPipelineId(context.getPlannerConfiguration().getPipelineId())
            .setNodeId(functionCall.getId())
            .setFunctionName(NAME)
            .build();

    SerializableRecordDescriptor inputDescriptor = new SerializableRecordDescriptor(context.getDescriptorForArgument("input"));
    RunPhantomContainer phantomContainerFunction = new RunPhantomContainer(outputDescriptor, inputDescriptor, authToken, phantomHost, customFields, cefTypes, description, label, ownerId, containerName, severity, sensitivity, status, containerType);

    String name = String.format("%s(action-name: %s)", NAME, containerName);
    return input.map(phantomContainerFunction)
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

    // SchemaType outSchema = schemaBuilder.build();
    //return new CollectionType(new RecordType(outSchema));

    schemaBuilder.addField(PHANTOM_CONTAINER_RESULT_FIELD_NAME, StringType.INSTANCE);
    return new CollectionType(new RecordType(schemaBuilder.build()));
  }

  private static class RunPhantomContainer extends RichMapFunction<Tuple, Tuple> {

    private static final long serialVersionUID = 1L;
    private final SerializableRecordDescriptor inputDescriptor;
    private final RecordDescriptor outputDescriptor;
    private final String authToken;
    private final String phantomHost;
    private final ObjectMapper mapper = new ObjectMapper();
    private final PhantomContainerFunction.customFieldsMap customFields;
    private final Map<String, String> cefTypes;
    private final String description;
    private final String label;
    private final String ownerId;
    private final String containerName;
    private final String severity;
    private final String sensitivity;
    private final String status;
    private final String containerType;

    private RunPhantomContainer(RecordDescriptor outputDescriptor, SerializableRecordDescriptor inputDescriptor, String authToken, String phantomHost, PhantomContainerFunction.customFieldsMap customFields, Map<String, String> cefTypes, String description, String label, String ownerId, String containerName, String severity, String sensitivity, String status, String containerType) {
      this.inputDescriptor = inputDescriptor;
      this.outputDescriptor = outputDescriptor;
      this.authToken = authToken;
      this.phantomHost = phantomHost;
      this.customFields = customFields;
      this.cefTypes = cefTypes;
      this.description = description;
      this.label = label;
      this.ownerId = ownerId;
      this.containerName = containerName;
      this.severity = severity;
      this.sensitivity = sensitivity;
      this.status = status;
      this.containerType = containerType;


    }

    // This function is called for every record that the function processes
    @Override
    public Tuple map(Tuple tuple) throws Exception {

      Record inputRecord = new Record(inputDescriptor.toRecordDescriptor(), tuple);
      Record outputRecord = new Record(outputDescriptor);
      String container_id="";
      String final_result="";

      Map<String, String> customFieldsMap = customFields.get(inputDescriptor, tuple);
      String[] action_result;
      action_result = PostActionRun(phantomHost, authToken, customFieldsMap, description, label, ownerId, containerName, severity, sensitivity, status, containerType ) ;
      if (action_result[0].equals("200")) {
        //Capture Run ID
        String id = ExtractID(action_result[1]);
        //Capture Action Results
        String[] container_result;
        container_result =  PostContainer(phantomHost, authToken, customFieldsMap, cefTypes, id);
        //if response is captured

        if (container_result[0].equals("200")) {
          container_id = ExtractID(container_result[1]);
          final_result="{\"container_id\":" + id + ", \"artifact_id\":" + container_id + "}";
          // copy all fields from the input record to the output record, except for the "phantom_action_result" field (if it exists)
          copyFields(inputRecord, outputRecord, PHANTOM_CONTAINER_RESULT_FIELD_NAME);
          // add the phantom action result field
          outputRecord.put(PHANTOM_CONTAINER_RESULT_FIELD_NAME, final_result);
        }
      }

      //return tuple;
      return outputRecord.getTuple();
    }
    private void copyFields(Record from, Record to, String excluded) {
      for (String fieldName : from.getFieldNames()) {
        if (!fieldName.equals(excluded)) {
          to.put(fieldName, from.get(fieldName));
        }
      }
    }

    private String ExtractID(String response) throws Exception {
      String id= "";
      Pattern p = Pattern.compile("\"id\":[^\\d]+([0-9]+)");
      //Pattern p = Pattern.compile("action_run_id[^\\d]+([0-9]+),");
      Matcher m = p.matcher(response);
      if (m.find()) {
//        System.out.println(m.group(1));
        id = m.group(1);
      } else {
        logger.error("RUN ID not found");
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

    private String[] PostContainer(String phantomHost, String authToken, Map<String, String> customFields, Map<String, String> cefTypes, String id) throws Exception {

      URL urlForPostRequest = new URL("https://" + phantomHost + "/rest/artifact");
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

      String cFields = mapper.writeValueAsString(customFields);
      String c1 = mapper.writeValueAsString(cefTypes);
      String c2=c1.replaceAll("\":\"","\":[\"");
      String c3=c2.replaceAll("\"}","\"]}");
      String cTypes=c3.replaceAll("\",","\"],");
      final String POST_PARAMS = "{ \"cef\":" + cFields + ", \"cef_types\": " + cTypes + ", \"container_id\": " + id + ", \"run_automation\": true }";

      connection.setDoOutput(true);
      OutputStream os = connection.getOutputStream();
      os.write(POST_PARAMS.getBytes());
      os.flush();
      os.close();


      int responseCode = connection.getResponseCode();
      logger.debug("Post artifact run response code: {} POST_PARAMS={}", responseCode, POST_PARAMS);

      if (responseCode >= 400) {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(connection.getErrorStream()));
        StringBuffer response = new StringBuffer();
        while ((readLine = in .readLine()) != null) {
          response.append(readLine);
        } in .close();
        // print result
        logger.error("Failed to send artifact request: {} {}", response.toString(), POST_PARAMS);
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
        ans_returned[1] =response_action;
        ans_returned[0] = Integer.toString(responseCode);

      } else {
        logger.error("Failed to get send artifact request: {}", responseCode);
      }
      return ans_returned;

    }

    private String[] PostActionRun(String phantomHost, String authToken, Map<String, String> customFields, String description, String label, String ownerId,  String containerName, String severity, String sensitivity, String status, String containerType) throws Exception {

      URL urlForPostRequest = new URL("https://" + phantomHost + "/rest/container");
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

      String cFields = mapper.writeValueAsString(customFields);

      final String POST_PARAMS = "{ \"customFields\": [" + cFields + "],  \"description\" : \"" + description + "\", \"name\" : \"" + containerName + "\" , \"owner_id\": \"" + ownerId + "\", \"label\": \"" + label + "\", \"sensitivity\" : \"" + sensitivity + "\", \"severity\" : \"" + severity + "\", \"status\" : \"" + status + "\", \"container_type\" : \"" + containerType + "\"}";

      connection.setDoOutput(true);
      OutputStream os = connection.getOutputStream();
      os.write(POST_PARAMS.getBytes());
      os.flush();
      os.close();


      int responseCode = connection.getResponseCode();
      ans_returned[0] =Integer.toString(responseCode);
      logger.debug("Post action run response code: {}", responseCode);

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
        logger.error("Failed to get send action request: {} PostParams={}", responseCode, POST_PARAMS);
      }
      return ans_returned;

    }
  }
  private static class customFieldsMap implements Serializable {
    private static final long serialVersionUID = 1L;
    private Map<String, String> mapLiteral;
    private Map<ScalarFunctionWrapper<String>, ScalarFunctionWrapper<String>> mapExpression;

    private customFieldsMap(Map<String, String> mapLiteral, Map<ScalarFunctionWrapper<String>, ScalarFunctionWrapper<String>> mapExpression) {
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
