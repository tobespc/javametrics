/*******************************************************************************
 * Copyright 2017 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package com.ibm.javametrics.web;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;

import com.ibm.javametrics.Javametrics;
import com.ibm.javametrics.JavametricsListener;

/**
 * Registers as a JavametricsListener to receive metrics data, processes the
 * data and sends the output to any registered emitters
 *
 */
public class DataHandler implements JavametricsListener {

    private static DataHandler instance = null;
    private Set<Emitter> emitters = new HashSet<Emitter>();

    private HttpDataAggregator aggregateHttpData;

    private static DataHandler getInstance() {
        if (instance == null) {
            instance = new DataHandler();
        }
        return instance;
    }

    protected DataHandler() {
        this.aggregateHttpData = new HttpDataAggregator();
    }

    public void addEmitter(Emitter emitter) {
        emitters.add(emitter);
        /*
         * adding as listener has the side effect of sending history which is
         * required for any newly registered emitter to see the Environment daya
         */
        Javametrics.getInstance().addListener(this);
    }

    public static void registerEmitter(Emitter emitter) {
        getInstance().addEmitter(emitter);
    }

    public void removeEmitter(Emitter emitter) {
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            Javametrics.getInstance().removeListener(this);
        }
    }

    public static void deregisterEmitter(Emitter emitter) {
        getInstance().removeEmitter(emitter);
    }

    public void emit(String message) {
        emitters.forEach((emitter) -> {
            emitter.emit(message);
        });
    }

    @Override
    public void receive(String pluginName, String data) {
        if (pluginName.equals("api")) {
            List<String> split = splitIntoJSONObjects(data);
            for (Iterator<String> iterator = split.iterator(); iterator.hasNext();) {
                String jsonStr = iterator.next();
                JsonReader jsonReader = Json.createReader(new StringReader(jsonStr));
                try {
                    JsonObject jsonObject = jsonReader.readObject();
                    String topicName = jsonObject.getString("topic", null);
                    if (topicName != null) {
                        if (topicName.equals("http")) {
                            synchronized (aggregateHttpData) {
                                aggregateHttpData.aggregate(jsonObject.getJsonObject("payload"));
                            }
                        } else {
                            emit(jsonObject.toString());
                        }
                    }
                } catch (JsonException je) {
                    // Skip this object, log the exception and keep trying with
                    // the rest of the list
                    je.printStackTrace();
                }
            }
            emitHttp();
        }
    }

    private void emitHttp() {
        HttpDataAggregator httpData;
        String httpUrlData;
        synchronized (aggregateHttpData) {
            httpData = aggregateHttpData.getCurrent();
            if (aggregateHttpData.total == 0) {
                httpData.time = System.currentTimeMillis();
            }
            httpUrlData = aggregateHttpData.urlDatatoJsonString();
            aggregateHttpData.clear();
        }
        emit(httpData.toJsonString());
        emit(httpUrlData);
    }

    /**
     * Split a string of JSON objects into multiple strings
     * 
     * @param data
     * @return
     */
    private List<String> splitIntoJSONObjects(String data) {
        List<String> strings = new ArrayList<String>();
        int index = 0;
        // Find first opening bracket
        while (index < data.length() && data.charAt(index) != '{') {
            index++;
        }
        int closingBracket = index + 1;
        int bracketCounter = 1;
        while (index < data.length() - 1 && closingBracket < data.length()) {
            // Find the matching bracket for the bracket at location 'index'
            boolean found = false;
            if (data.charAt(closingBracket) == '{') {
                bracketCounter++;
            } else if (data.charAt(closingBracket) == '}') {
                bracketCounter--;
                if (bracketCounter == 0) {
                    // found matching bracket
                    found = true;
                }
            }
            if (found) {
                strings.add(data.substring(index, closingBracket + 1));
                index = closingBracket + 1;
                // Find next opening bracket and reset counters
                while (index < data.length() && data.charAt(index) != '{') {
                    index++;
                }
                closingBracket = index + 1;
                bracketCounter = 1;
            } else {
                closingBracket++;
            }
        }
        return strings;
    }

}
