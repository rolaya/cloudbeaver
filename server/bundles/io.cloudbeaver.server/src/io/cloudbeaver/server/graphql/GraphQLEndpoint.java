/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2020 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cloudbeaver.server.graphql;

import com.google.gson.*;
import graphql.*;
import graphql.execution.AsyncExecutionStrategy;
import graphql.execution.DataFetcherExceptionHandlerResult;
import graphql.execution.ExecutionPath;
import graphql.execution.instrumentation.SimpleInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.language.SourceLocation;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.cloudbeaver.DBWebException;
import io.cloudbeaver.WebServiceUtils;
import io.cloudbeaver.api.DBWService;
import io.cloudbeaver.api.DBWServiceGraphQL;
import io.cloudbeaver.server.CloudbeaverApplication;
import io.cloudbeaver.server.registry.WebServiceDescriptor;
import io.cloudbeaver.server.registry.WebServiceRegistry;
import org.jkiss.dbeaver.Log;
import org.jkiss.utils.IOUtils;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class GraphQLEndpoint extends HttpServlet {

    private static final Log log = Log.getLog(GraphQLEndpoint.class);

    private static final String HEADER_ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String HEADER_ACCESS_CONTROL_ALLOW_HEADERS = "Access-Control-Allow-Headers";
    private static final String HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";

    private static final String CORE_SCHEMA_FILE_NAME = "schema/schema.graphqls";

    private final GraphQL graphQL;

    private static Gson gson = new GsonBuilder()
        .serializeNulls()
        .setPrettyPrinting()
        .create();

    public GraphQLEndpoint() {
        GraphQLSchema schema = buildSchema();

        graphQL = GraphQL
            .newGraphQL(schema)
            .instrumentation(new WebInstrumentation())
            .queryExecutionStrategy(new WebExecutionStrategy())
            .mutationExecutionStrategy(new WebExecutionStrategy())
            .build();
    }

    private GraphQLSchema buildSchema() {
        SchemaParser schemaParser = new SchemaParser();
        TypeDefinitionRegistry parsedSchema = new TypeDefinitionRegistry();

        try (InputStream schemaStream = WebServiceUtils.openStaticResource(CORE_SCHEMA_FILE_NAME)) {
            try (Reader schemaReader = new InputStreamReader(schemaStream)) {
                parsedSchema.merge(schemaParser.parse(schemaReader));
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading core schema", e);
        }

        for (WebServiceDescriptor wsd : WebServiceRegistry.getInstance().getWebServices()) {
            DBWService instance;
            try {
                instance = wsd.getInstance();
            } catch (Exception e) {
                log.error(e);
                continue;
            }
            if (instance instanceof DBWServiceGraphQL) {
                try {
                    TypeDefinitionRegistry typeDefinition = ((DBWServiceGraphQL) instance).getTypeDefinition();
                    if (typeDefinition != null) {
                        log.debug("Adding web service '" + wsd.getId() + "' GQL schema");
                        parsedSchema.merge(typeDefinition);
                    }
                } catch (DBWebException e) {
                    log.warn("Error obtaining web service '" + wsd.getId() + "' type definitions", e);
                }
            }
        }

        SchemaGenerator schemaGenerator = new SchemaGenerator();
        GraphQLModel wiring = new GraphQLModel();
        return schemaGenerator.makeExecutableSchema(parsedSchema, wiring.buildRuntimeWiring());
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        setDevelHeaders(request, response);
    }

    private void setDevelHeaders(HttpServletRequest request, HttpServletResponse response) {
        if (CloudbeaverApplication.getInstance().isDevelMode()) {
            // response.setHeader(HEADER_ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            // response.setHeader(HEADER_ACCESS_CONTROL_ALLOW_HEADERS, "*");
            // response.setHeader(HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS, "*");

            String referrer = request.getHeader("referer");

            try {
                URL url = new URL(referrer);
                String protocol = url.getProtocol();
                String host = url.getHost();
                int port = url.getPort();

                String origin;

                // if the port is not explicitly specified in the input, it will be -1.
                if (port == -1) {
                    origin = String.format("%s://%s", protocol, host);
                } else {
                    origin = String.format("%s://%s:%d", protocol, host, port);
                }

                // for local machine must be defined explicitly:
                response.setHeader(HEADER_ACCESS_CONTROL_ALLOW_ORIGIN, origin);
                response.setHeader(HEADER_ACCESS_CONTROL_ALLOW_HEADERS, "Set-Cookie, Content-Type");
                response.setHeader(HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            } catch (Throwable t) {}
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String postBody = IOUtils.readToString(request.getReader());
        JsonElement json = gson.fromJson(postBody, JsonElement.class);
        if (json instanceof JsonArray) {
            setDevelHeaders(request, response);
            response.setContentType(GraphQLConstants.CONTENT_TYPE_JSON_UTF8);
            response.getWriter().print("[\n");

            JsonArray array = (JsonArray)json;
            int reqCount = 0;
            for (int i = 0; i < array.size(); i++) {
                if (reqCount > 0) {
                    response.getWriter().print(",\n");
                }
                JsonElement item = array.get(i);
                if (item instanceof JsonObject) {
                    executeSingleQuery(request, response, (JsonObject) item);
                    reqCount++;
                }
            }

            response.getWriter().print("\n]");
        } else if (json instanceof JsonObject) {
            JsonObject reqObject = (JsonObject) json;
            executeSingleQuery(request, response, reqObject);
        } else {
            response.sendError(400, "Bad JSON request");
        }
    }

    private void executeSingleQuery(HttpServletRequest request, HttpServletResponse response, JsonObject reqObject) throws IOException {
        JsonElement query = reqObject.get("query");
        if (query == null) {
            response.sendError(400, "Query not specified");
            return;
        }
        JsonElement varJSON = reqObject.get("variables");
        Map<String, Object> variables = varJSON == null ? null : gson.fromJson(varJSON, Map.class);

        JsonElement operNameJSON = reqObject.get("operationName");

        executeQuery(request, response, query.getAsString(), variables, operNameJSON == null || operNameJSON instanceof JsonNull ? null : operNameJSON.getAsString());
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String path = request.getPathInfo();
        if (path == null) {
            path = request.getServletPath();
        }
        boolean develMode = CloudbeaverApplication.getInstance().isDevelMode();

        if (path.contentEquals("/schema.json") && develMode) {
            executeQuery(request, response, GraphQLConstants.SCHEMA_READ_QUERY, null, null);
        } else if (path.contentEquals("/console") && develMode) {
            try (InputStream consolePageStream = WebServiceUtils.openStaticResource("static/graphiql/index.html")) {
                IOUtils.copyStream(consolePageStream, response.getOutputStream());
            }
        } else {
            String query = request.getParameter("query");
            if (query != null) {
                executeQuery(request, response, query, null, request.getParameter("operationName"));
            } else {
                response.sendError(400, "Bad GET request");
            }
        }
    }

    private void executeQuery(HttpServletRequest request, HttpServletResponse response, String query, Map<String, Object> variables, String operationName) throws IOException {
        GraphQLContext context = new GraphQLContext.Builder()
            .of("request", request)
            .of("response", response)
            .build();
        ExecutionInput.Builder contextBuilder = ExecutionInput.newExecutionInput()
            .context(context)
            .query(query);
        if (variables != null) {
            contextBuilder.variables(variables);
        }
        if (operationName != null) {
            contextBuilder.operationName(operationName);
        }

        ExecutionInput executionInput = contextBuilder.build();
        ExecutionResult executionResult = graphQL.execute(executionInput);

        Map<String, Object> resJSON = executionResult.toSpecification();
        String resString = gson.toJson(resJSON);
        setDevelHeaders(request, response);
        response.setContentType(GraphQLConstants.CONTENT_TYPE_JSON_UTF8);
        response.getWriter().print(resString);
    }

    private class WebInstrumentation extends SimpleInstrumentation {
        @Override
        public CompletableFuture<ExecutionResult> instrumentExecutionResult(ExecutionResult executionResult, InstrumentationExecutionParameters parameters) {
            return super.instrumentExecutionResult(executionResult, parameters);
        }

        @Override
        public DataFetcher<?> instrumentDataFetcher(DataFetcher<?> dataFetcher, InstrumentationFieldFetchParameters parameters) {
            return dataFetcher;
//            return environment -> {
//                try {
//                    return dataFetcher.get(environment);
//                } catch (Exception e) {
//                    log.debug(e);
//                    throw e;
//                }
//            };
        }
    }

    private class WebExecutionStrategy extends AsyncExecutionStrategy {
        public WebExecutionStrategy() {
            super(handlerParameters -> {
                Throwable exception = handlerParameters.getException();
                log.debug(exception);

                SourceLocation sourceLocation = handlerParameters.getSourceLocation();
                ExecutionPath path = handlerParameters.getPath();

                DataFetcherExceptionHandlerResult.Builder handlerResult = DataFetcherExceptionHandlerResult.newResult();
                if (exception instanceof GraphQLError) {
                    if (exception instanceof DBWebException) {
                        ((DBWebException) exception).setPath(path.toList());
                        ((DBWebException) exception).setLocations(Collections.singletonList(sourceLocation));
                    }
                    return handlerResult.error((GraphQLError) exception).build();
                } else {
                    return handlerResult.error(new ExceptionWhileDataFetching(path, exception, sourceLocation)).build();
                }
            });
        }
    }
}