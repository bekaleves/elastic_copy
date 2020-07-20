/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.ml.integration;

import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.test.SecuritySettingsSourceField;
import org.elasticsearch.test.rest.ESRestTestCase;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.elasticsearch.xpack.core.security.authc.support.UsernamePasswordToken.basicAuthHeaderValue;

public class ClassificationEvaluationWithSecurityIT extends ESRestTestCase {
    private static final String BASIC_AUTH_VALUE_SUPER_USER =
        basicAuthHeaderValue("x_pack_rest_user", SecuritySettingsSourceField.TEST_PASSWORD_SECURE_STRING);

    @Override
    protected Settings restClientSettings() {
        return Settings.builder().put(ThreadContext.PREFIX + ".Authorization", BASIC_AUTH_VALUE_SUPER_USER).build();
    }

    private static void setupDataAccessRole(String index) throws IOException {
        Request request = new Request("PUT", "/_security/role/test_data_access");
        request.setJsonEntity("{"
            + "  \"indices\" : ["
            + "    { \"names\": [\"" + index + "\"], \"privileges\": [\"read\"] }"
            + "  ]"
            + "}");
        client().performRequest(request);
    }

    private void setupUser(String user, List<String> roles) throws IOException {
        String password = new String(SecuritySettingsSourceField.TEST_PASSWORD_SECURE_STRING.getChars());

        Request request = new Request("PUT", "/_security/user/" + user);
        request.setJsonEntity("{"
            + "  \"password\" : \"" + password + "\","
            + "  \"roles\" : [ " + roles.stream().map(unquoted -> "\"" + unquoted + "\"").collect(Collectors.joining(", ")) + " ]"
            + "}");
        client().performRequest(request);
    }

    public void testEvaluate_withSecurity() throws Exception {
        String index = "test_data";
        Request createDoc = new Request("POST", index + "/_doc");
        createDoc.setJsonEntity(
            "{\n" +
                "  \"is_outlier\": 0.0,\n" +
                "  \"ml.outlier_score\": 1.0\n" +
                "}"
        );
        client().performRequest(createDoc);
        Request refreshRequest = new Request("POST", index + "/_refresh");
        client().performRequest(refreshRequest);
        setupDataAccessRole(index);
        setupUser("ml_admin", Collections.singletonList("machine_learning_admin"));
        setupUser("ml_admin_plus_data", Arrays.asList("machine_learning_admin", "test_data_access"));
        String mlAdmin = basicAuthHeaderValue("ml_admin", SecuritySettingsSourceField.TEST_PASSWORD_SECURE_STRING);
        String mlAdminPlusData = basicAuthHeaderValue("ml_admin_plus_data", SecuritySettingsSourceField.TEST_PASSWORD_SECURE_STRING);
        Request evaluateRequest = buildRegressionEval(index, mlAdmin, mlAdminPlusData);
        client().performRequest(evaluateRequest);

        Request failingRequest = buildRegressionEval(index, mlAdminPlusData, mlAdmin);
        expectThrows(ResponseException.class, () -> client().performRequest(failingRequest));
    }

    private static Request buildRegressionEval(String index, String primaryHeader, String secondaryHeader) {
        Request evaluateRequest = new Request("POST", "_ml/data_frame/_evaluate");
        evaluateRequest.setJsonEntity(
            "{\n" +
                "  \"index\": \"" + index + "\",\n" +
                "  \"evaluation\": {\n" +
                "    \"regression\": {\n" +
                "      \"actual_field\": \"is_outlier\",\n" +
                "      \"predicted_field\": \"ml.outlier_score\"\n" +
                "    }\n" +
                "  }\n" +
                "}\n"
        );
        RequestOptions.Builder options = evaluateRequest.getOptions().toBuilder();
        options.addHeader("Authorization", primaryHeader);
        options.addHeader("es-secondary-authorization", secondaryHeader);
        evaluateRequest.setOptions(options);
        return evaluateRequest;
    }
}
