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
package io.cloudbeaver.service.navigator;

import graphql.schema.idl.TypeRuntimeWiring;
import io.cloudbeaver.DBWebException;
import io.cloudbeaver.service.DBWBindingContext;
import io.cloudbeaver.service.WebServiceBindingBase;
import io.cloudbeaver.service.navigator.impl.WebServiceNavigator;
import io.cloudbeaver.service.sql.WebServiceBindingSQL;

import java.util.Map;

/**
 * Web service implementation
 */
public class WebServiceBindingNavigator extends WebServiceBindingBase<DBWServiceNavigator> {

    public WebServiceBindingNavigator() {
        super(DBWServiceNavigator.class, new WebServiceNavigator(), "schema/service.navigator.graphqls");
    }

    @Override
    public void bindWiring(DBWBindingContext model) throws DBWebException {
        model.getQueryType()
            .dataFetcher("navNodeChildren", env -> getService(env).getNavigatorNodeChildren(
                getWebSession(model, env),
                env.getArgument("parentPath"),
                env.getArgument("offset"),
                env.getArgument("limit"),
                env.getArgument("onlyFolders")))
            .dataFetcher("navNodeInfo", env -> getService(env).getNavigatorNodeInfo(
                getWebSession(model, env),
                env.getArgument("nodePath")))
            .dataFetcher("navRefreshNode", env -> getService(env).refreshNavigatorNode(
                getWebSession(model, env),
                env.getArgument("nodePath")
            ))
            .dataFetcher("navGetStructContainers", env -> getService(env).getStructContainers(
                getWebConnection(model, env),
                env.getArgument("catalog")
            ));

        model.getRuntimeWiring().type(TypeRuntimeWiring.newTypeWiring("DatabaseObjectInfo")
            .dataFetcher("properties", env -> {
                Map<String, Object> filterProps = env.getArgument("filter");
                WebPropertyFilter filter = filterProps == null ? null : new WebPropertyFilter(filterProps);
                return ((WebDatabaseObjectInfo)env.getSource()).filterProperties(filter);
            })
        );
    }

}