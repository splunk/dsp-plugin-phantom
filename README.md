# dsp-plugin-phantom
## Plugin Overview
The Phantom DSP plugin is built using the Java SDK. It comes with three functions: 
1. Trigger Phantom Action: this is a bidirectional integration that allows users to execute any Phantom action and capture the corrsponding response.
2. Trigger Phantom Playbook: allows to trigger any Phantom playbook execution from a DSP pipeline
3. Trigger Phantom Case or Event: allows to trigger an event or a case in Phantom and pass to it any field dynamically. The fields passed would be used in the artifact creation on that event or case. Fields can be any standard CEF field or custom one. 

For deploying the plugin, you can either use the pre-compiled code jar file by following steps on 'Option 1' of the installation section or download the java code, customize to your need and compile the code yourself in 'Option 2' of that section. 

## Plugin Compatibility
This plugin was tested against Phantom 4.8 and 4.9 using DSP SDK 1.1.0 and 1.2.0. 


## Plugin Installation
### Option 1: Install jar file (pre-compiled code)
#### Pre-requisite
1. Install and configure Sclood v4.0 or higher. The steps to configure Scloud can be found on the [DSP documentation](https://docs.splunk.com/Documentation/DSP/1.1.0/Admin/AuthenticatewithSCloud).
2. Verify you can login via Scloud. Login in verbose mode to generate a token by running the following command:
```
./scloud login --verbose
```
3. Save the token as you will need it for subsequent steps
#### Download the plugin Jar file
Download the dsp-plugin-functions.jar file from this GitHub repo.
#### Upload the jar file to you DSP environment

1. Set the variable values of your DSP Host/IP as well as the authentication token (acquired in pre-requisite section).
```
export DSP_HOST=<dsp cluster IP/hostname>
export TOKEN=<token value>
```
2. Register the plugin by running the following curl command. 
```
curl -X POST --insecure --header 'Content-Type: application/json' --header 'Accept: application/json' -H "Authorization: Bearer $TOKEN" -d '{ "description" :  " DSP Phantom Plugin", "name"  :  "phantom-action" }' "https://$DSP_HOST:31000/default/streams/v3beta1/plugins"
```
The command should return an id that you need to use in the next step. 

4. Upload the plugin by running the following command. Make sure you replace '<Plugin ID>' with the id saved from previous step and "$PATH_TO_JAR_FILE" with downloaded jar file location on local disk. 
```
curl --header 'Content-Type: multipart/form-data'  -X POST -H "Authorization: Bearer $TOKEN" --header 'Accept: application/json' "https://$DSP_HOST:31000/default/streams/v3beta1/plugins/<PLUGIN_ID>/upload" --insecure -F 'pluginJar=@<path to jar file>'
```
5. Login and Refresh the Data Stream Processor UI. You should now be able to see3 newly added plugins "Phantom Container", "Phantom Playbook" and "Phantom Action".
```
curl -X POST --insecure --header 'Content-Type: application/json' --header 'Accept: application/json' -H "Authorization: Bearer $TOKEN" -d '{ "description" :  " DSP's Phantom Plugin", "name"  :  "phantom-action" }' "https://$DSP_HOST:31000/default/streams/v3beta1/getPlugins"
```
6. In case you want to update to a new version of the plugin, you will need to delete the existing plugin from your DSP environment before re-uploading. Start by capturing the plugin id by running the following curl command:
```
curl -X GET --insecure --header 'Content-Type: application/json' --header 'Accept: application/json' -H "Authorization: Bearer $TOKEN" "https://$DSP_HOST:31000/default/streams/v3beta1/plugins"
```
 Next delete the plugin. Replace "<plugin_id>" with the id captured from previous step
```
curl --header 'Content-Type: multipart/form-data' -X DELETE --insecure -H "Authorization: Bearer $TOKEN" --header 'Accept: application/json' "https://$DSP_HOST:31000/default/streams/v3beta1/plugins/<plugin_id>"
```

### Option 2: Compile the code youreself
#### Pre-requisite
1. Install Java version 8
2. Clone the repository from GitHub: [DSP Plugins](https://github.com/splunk/dsp-plugins-sdk/). Check out the git tag that corresponds to the version of DSP that you are using, for example, 'release-1.1'.
3. Download the three .java files that make up this plugin from this GitHub repo (DSP Phantom plugin) .
- PhantomActionFunction.java
- PhantomContainerFunction.java
- PhantomPlaybookFunction.java
4. Place the three files you just downloaded and put them in the local SDK directory you just cloned under.
```
⁨data-pipelines-plugin-template⁩ ▸ ⁨dsp-plugin-functions⁩ ▸ ⁨src⁩ ▸ ⁨main⁩ ▸ ⁨java⁩ ▸ ⁨com⁩ ▸ ⁨splunk⁩
```
Note: if the directory is not already created, please do so.

#### Compile, Register and Upload the Plugin
1. Update the gradle.properties file.
- `SCLOUD_TOKEN_FILE: Path to your SCloud access token. This file must contain only the access token with no additional metadata.`
- `PLUGIN_UPLOAD_SERVICE_PROTOCOL:	http or https`
- `PLUGIN_UPLOAD_SERVICE_HOST: The hostname or IP address used to reach the Data Stream Processor API Service, for example: 10.130.33.112.`
- `PLUGIN_UPLOAD_SERVICE_PORT: The port associated with your host, for example 31000.`
2. Build the plugin examples by running the following command: 
 ```
 ./gradlew dsp-plugin-functions:build
 ```
3. Register the DSP plugin.
 ```
 /gradlew  registerPlugin -PSDK_PLUGIN_NAME=phantom-action
 ```
4. Register the DSP plugin.
```
./gradlew uploadPlugin -PPLUGIN_ID=ee6fe69b-8a26-4dd5-84b8-11bcb5af4ff7 -PSDK_PLUGIN_DESC="Phantom DSP Plugin" 
#Response: 
Registered plugin: phantom-action. Response: [pluginId:0613b04d-9269-4e4c-a240-9f415f5514ca, name:phantom-action, 
tenantId:default, lastUpdateDate:1566498405383, lastUpdateUserId:0, isDeleted:false]
```
5. Capture the pluginId from step #5 and use in the following step
6. Upload the built JAR to the Data Stream Processor. Make sure you replace <Id> with the pluginId returned on step#5. 
  ```
./gradlew uploadPlugin -PPLUGIN_ID=<Id>
  ```
7. Refresh the Data Stream Processor UI. You should not be able to 3 newly added plugins "Phantom Container", "Phantom Playbook" and "Phantom Action".
  
  For more information on how to register, upload or delete the plugin pleasde refer to the [Plugin SDK documentation](https://docs.splunk.com/Documentation/DSP/1.1.0/User/PluginSDK/).

## Plugin Usage
This plugin comes with three functions: 
-Trigger Phantom Event or Case
-Trigger Phantom Action
-Trigger Phantom Playbook

### Pre-requisite: Generate Phantom Token
1. Login to you Phantom environment
2. From the top left menu, go to "Administration"
3. Navigate to User Management -> Users
4. Create a new user by setting a username and selecting the type to be 'automation'
5. Create the user
6. Go back and edit the user and note down the ph-auth-token value along with the server IP address for upcoming steps. 
```
{
  "ph-auth-token": "xxxxxxxxx=",
  "server": "https://10.10.10.10"
}
```

### Trigger Phantom Event or Case Function
This function allows you to create event or case along with an artifcat that gets mapped to it. The fields that you pass to this function will be used to create the Phantom artificat of the event/case triggered. This plugin returns a json string with the corresponding event/case id and artifact id that got created in Phantom as a result of the execution. This table highlights all the fields that that function needs in order to run. 'Dynamic' fields are fields that accept values that can be dynamically caprured from previous steps in the pipeline. 

#### Function  Input Arguments
| Argument | Description | Required | Dynamic | Example Value |
| --- | ----- | --- | --- | ----- |
| `auth_token` | Phantom authentication token generated per pre-requisite section | Yes | No | 1LUb6vEj7kxxxxxxxxxxAbcCgHFy5XMWByCs= | 
| `host` | IP/hostname of Phantom cluster | Yes | No | 54.3.2.1 |
| `fields` | Key-value pair(s) that represent CEF fields and their corresponding values used to create the artifact in the case or event. When using custom CEF fields, you will need to map that field to a CEF type using the field_type argument. Note that You can dynamically pass values from fields captured in previous steps of the pipeline. | Yes | Yes | ip="54.3.3.2" (use double quotes for static values) - ip=mydynamic_ip (no double quotes used for dynamic values) - my_custom_ip_field="54.1.1.2" (used for custom field, make sure you define field_type argument)|
| `name` | name of the Phantom case or event | Yes | Yes | Suspicious IP behavior|
| `description` | Description of the Phantom event or case | Yes | No | High traffic from suspicious IP |
| `owner_id` | Phantom username that the event or case needs to be assigned to | Yes | No | admin |
| `field_type` | Use this argument to define the CEF type of a custom fields. See [Artificat doc] (https://docs.splunk.com/Documentation/Phantom/4.9/PlatformAPI/RESTArtifacts) for more details. Keep Blank if all fields used are CEF fields  | No | No | my_custom_ip_field="ip" (make sure you use double quotes in value) |
| `type` | Defines the type of container. If set to 'default', you will create a Phantom event. If set to 'case', you will create a case. When kept blank (default value), will create an event.  | No | No | case |
| `status` | Status of event or Case. When kept blank, default value will be 'event'. Acceptable values: default or case | No | No | new |
| `severity` | Severity of case or event. When kept blank, default value will be 'medium'. Acceptable values: high, medium or low | No | No | low |
| `sensitivity` | Sensitivity of case or event. When kept blank, default value will be 'amber'. Acceptable values: red, amber, green or white  | No | No | red |
| `label` | Label associated with event or case created. When kept blank, default value will be 'events'. When using different value, label must be already created in Phantom | No | No | events |

#### Function Output Arguments
This function returns a json string that can be used in subsequents steps on the DSP pipeline. Use this returned value to merge it with the initial event in case you want to send that event to Splunk for drill down purposes to Phantom. 

| Retruned Field Name | Field Type | Description  | Example Value |
| --- | --- | ----- | ---- |
| phantom_container_result | string with json format | json representation of the event or case id along with the artifact id | {"container_id":214, "artifact_id":139} |

#### Sample Pipeline
- Sample pipeline that triggers a case in Phantom. This example illustrates how you can pass field values dynamically  and custom CEF fields as part of the artifact.

 ```
| from read_splunk_firehose() | eval body=cast(body, "string") | eval dynamicIP="54.10.10.10" | phantom_container fields_type={"my_custom_ip": "ip"} type="case" status="new" severity="high" sensitivity="red" label="events" auth_token="xxxxxxxxxxxxxxxxxxxxxx=" phantom_host="10.10.10.10" fields={"subject": "We are restarting the server", "my_custom_ip": dynamicIP, "to": "mygmailaddress@gmail.com", "body": "Alert"} name="Server down - restart process" description="Demo Container DSP" owner_id="admin";
 ```
 - Sample pipeline that triggers an event in Phantom. This example illustrates how you can capture results returned by function and merge it into the initial event body before sending the concatenated result to Splunk
 ```
| from read_splunk_firehose() | eval body=cast(body, "string") | eval dynamicIP="54.10.10.10" | phantom_container fields_type={"my_custom_ip": "ip"}  status="new" severity="high" sensitivity="red" label="events" auth_token="xxxxxxxxxxxxxxxxxxxxx=" phantom_host="10.10.10.10" fields={"subject": "We are restarting the server", "my_custom_ip": dynamicIP, "to": "mygmailaddress@gmail.com", "body": "Alert"} name="Server down - restart process" description="Demo Container DSP" owner_id="admin" | eval body=concat("{\"body\":\"", body, "\",\"phantom_response\":", phantom_container_result, "}") | into splunk_enterprise_indexes("xxxxxxxx-xxxxx-xxxx-xxxx-xxxxxxx", "main", "main");
 ```

### Trigger Phantom Action Function
This function allows you to run a Phantom action against an existing container (event or case). This plugin returns a json string returned by the action execution. The plugin can be used for containment (eg. block an IP address) or can be used for enriching pipeline events with action results (for eg. geolocate IP enrichment of event) 

#### Function Input Arguments
| Argument | Description | Required | Dynamic | Example Value |
| --- | ----- | --- | --- | ----- |
| `auth_token` | Phantom authentication token generated per pre-requisite section | Yes | No | 1LUb6vEj7kOp8xnxxxxxxxxxxxxxxxcCgHFy5XMWByCs= | 
| `host` | IP/hostname of Phantom cluster | Yes | No | 54.3.2.1 |
| `action_name` | name of action to execute. Name must match exactly the name defined in the Phantom App | Yes | No | geolocate ip|
| `container_id` | Container (event or case) id that the action will need to execute against. Container must be already created in Phantom for action to execute | Yes | No | 3 |
| `asset` | Phantom Asset name used when App was configured. Asset name must match exactly the name defined inside Phantom app | Yes | No | maxmind |
| `parameters` | Key-value pair(s) that represent input arguments needed by the action to execute. You can dynamically pass values from fields captured in previous steps of the pipeline | Yes | Yes | ip="54.3.3.2" (use double quotes for static values) - ip=mydynamic_ip (no double quotes used for dynamic values)|
| `app_id` | App id that corresponds to the App where the action is is defined. You can get the id from the Phantom url when you click on the App in question | Yes | No | 103 |
| `action_type` | Type of action executed. Values accepted: investigative, manual, correct, contain, etc. | Yes | No | investigative |
| `max_attempt` | Max number of attempts to query action results from Phantom after it was sent for execution. When set to blank, default value is 10  | No | Yes | 5 |
| `timeout` | Timeout value in seconds to query action results from Phantom after it was sent for execution. When set to blank, the default value is 30. | Yes | No | 60 |

#### Function Output Arguments
This function returns a json string that can be used in subsequents steps on the DSP pipeline. By capturing the returned results of this plugin execution, you can enrich pipeline events with the output of any action that can be executed in Phantom. A good example can be the enriching of IP addresses of your DSP events with geo location or  DSN reverse lookup information before sending the events to Splunk for indexing. It is VERY important to note that Phantom cannot scale at the speed of DSP so the enrichment can only be achieved against a subset or filtered set of events in the pipeline to avoid scalability issues 


| Retruned Field Name | Field Type | Description  | Example Value |
| --- | --- | ----- | ---- |
| phantom_action_result | string with json format | json representation of the results returned by the Phantom action execution | {"status": "success", "node_guid": "9e2d70de-1c7f-4428-86a7-df1c5eeb283b", "action_run": 7024, "extra_data": [], "container": 174, "app_name": "VirusTotal", "start_time": "2020-09-23T13:23:10.782000Z", "app": 14, "effective_user": null, "result_data": [{"status": "success", "parameter": {"ip": "3.216.107.103", "context": {"guid": "b691d246-aa6c-49f6-bf71-adc8db6b9350", "artifact_id": 0, "parent_action_run": []}}, "message": "Detected urls: 0", "data": [{"country": "US", "resolutions": [{"last_resolved": "2020-04-18 20:34:22", "hostname": "kono.mx"}, {"last_resolved": "2020-04-25 19:43:21", "hostname": "react.ddns.net"}, {"last_resolved": "2020-04-18 20:34:22", "hostname": "www.kono.mx"}], "response_code": 1, "detected_urls": [], "verbose_msg": "IP address in dataset"}], "summary": {"detected_urls": 0}}], "playbook_run": null, "asset": 6, "version": 1, "action": "ip reputation", "result_summary": {"total_objects_successful": 1, "total_objects": 1, "total_positives": 0}, "message": "'DSP' on asset 'virustotal': 1 action succeeded. (1)For Parameter: {\\"context\\":{\\"artifact_id\\":0,\\"guid\\":\\"b691d246-aa6c-49f6-bf71-adc8db6b9350\\",\\"parent_action_run\\":[]},\\"ip\\":\\"3.216.107.103\\"} Message: \\"Detected urls: 0\\"", "app_version": "2.0.8", "exception_occured": false, "id": 6900, "end_time": "2020-09-23T13:23:11.746000Z"} |

#### Sample Pipeline
- Sample pipeline that illustrates how you can use the Phantom Action function in order to trigger email alerts. This example executes SMTP App 'send email' action and passes the input fields that this action needs to execute: email body, subject and destination email.  
```
| from read_splunk_firehose() | phantom_action auth_token="xxxxxxxxxxxxxxxxxxs=" phantom_host="10.10.10.10" action_name="send email" container_id=214 asset="google" parameters={"to": "myemail@gmail.com", "body": "Please block user", "subject": "Suspicious user behavior"} app_id=42 action_type="investigative";
```

 - Sample pipeline that executes two Phantom actions in a row: Maxmind's "geolocate IP" and VirusTotal's "IP reputation". This example illustrates how you can run consecutive enrichments action in your pipeline and concat the corresponding results with the event body before sending outcome to Splunk for indexing.
```
| from read_splunk_firehose() | eval body=cast(body, "string") | phantom_action auth_token="xxxxxxxxxxxxx=" phantom_host="10.10.10.10" action_name="geolocate ip" container_id=174 asset="maxmind" parameters={"ip": "54.10.10.18"} app_id=103 action_type="investigative" | rename phantom_action_result AS geo_ip | phantom_action auth_token="xxxxxxxxxxxxx=" phantom_host="10.10.10.10" action_name="ip reputation" container_id=174 asset="virustotal" parameters={"ip": "54.10.10.18"} app_id=14 action_type="investigative" | rename phantom_action_result AS reputation_ip | eval body=concat("{\"body\":\"", body, "\", \"phantom_ip_geolocation\":", geo_ip, ", \"phantom_ip_reputation\":", reputation_ip, "}") | into splunk_enterprise_indexes("xxxxxxxx-xxxxx-xxxx-xxxx-xxxxxxx", "main", "main");
```

### Trigger Phantom Playbook Function
This function allows you to run a Phantom playbook against an existing container (event or case). This function does not return any results.

#### Function Input Arguments
| Argument | Description | Required | Dynamic | Example Value |
| --- | ----- | --- | --- | ----- |
| `auth_token` | Phantom authentication token generated per pre-requisite section | Yes | No | 1LUb6vEj7kOp8xnxxxxxxxxxxxxxxxcCgHFy5XMWByCs= | 
| `host` | IP/hostname of Phantom cluster | Yes | No | 54.3.2.1 |
| `playbook_id` | The id of the playbook to execute | Yes | No | 168 |
| `container_id` | Container (event or case) id that the action will need to execute against. Container must be already created in Phantom for action to execute | Yes | No | 3 |
| `scope` | Scope allows the playbook to be run against a specific set of artifacts. Values accepted can be "all" or "new" or a list of integers containing artifact Ids. When kept empty, default value used is 'all' | No | No | new |

#### Function Output Arguments
This function does not return any data


#### Sample Pipeline

 - Sample pipeline that executes a playbook against an existing container.
```
| from read_splunk_firehose() | phantom_playbook auth_token="xxxxxxxxxxxxxxxxxxx=" phantom_host="10.10.10.10" playbook_id=68 container_id=214;
```

### Throttling
As mentioned in previous section, it is important to note that DSP and Phantom dont have the same scalability or speed of execution. Whether you are using the plugin for taking automated actions/containments or for enriching events in your pipeline it is advised to place a filtering or detection condition prior to using any function in this plugin. It could be sensible as well to apply throttling where you see a possibility of duplicate events in the pipeline. DSP has a a function 'Aggregate with trigger' that can be used for the same. Below is a sample pipeline that throttle email alerts for 60 seconds and triggers one and only one action during that time window the moment the count is greater than 0.

```
| from read_splunk_firehose() | eval body=cast(body, "string")  | aggregate_and_trigger keys=[body] timestamp=timestamp size=60000L slide=60000L grace_period=0L trigger_count=1L trigger_interval=0L trigger_time_type="EventTime" aggregations=[count(body) AS count] predicate=count>0 trigger_max_fire_count=1L custom_fields=["triggered because event count > 0" AS action] | phantom_action auth_token="xxxxxxxxxxxxx=" phantom_host="myphantomhostname" action_name="send email" container_id=6 asset="google" parameters={"body": suspiciousIP, "to": "myemail@gmail.com", "subject": "Please kill the process alert"} app_id=142 action_type="investigative";
  
```

------------------------------------------------------
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

