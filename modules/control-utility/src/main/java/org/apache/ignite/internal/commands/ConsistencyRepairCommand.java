/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.commands;

import lombok.Data;
import org.apache.ignite.internal.commands.api.ExperimentalCommand;
import org.apache.ignite.internal.commands.api.PositionalParameter;

/**
 *
 */
@Data
public class ConsistencyRepairCommand implements ExperimentalCommand {
    /** */
    @PositionalParameter(description = "Cache to be checked/repaired")
    private String cacheName;

    /** */
    @PositionalParameter(index = 1, description = "Cache's partition to be checked/repaired")
    private long partition;

    /** {@inheritDoc} */
    @Override public String description() {
        return "Check/Repair cache consistency using Read Repair approach";
    }
}
