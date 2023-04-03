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

package org.apache.ignite.internal.commands.snapshot;

import lombok.Data;
import org.apache.ignite.internal.commands.api.Command;
import org.apache.ignite.internal.commands.api.Parameter;
import org.apache.ignite.internal.commands.api.PositionalParameter;

/**
 *
 */
@Data
public class SnapshotCreateCommand implements Command {
    /** */
    @PositionalParameter(description = "Snapshot name. " +
        "In case incremental snapshot (--incremental) full snapshot name must be provided")
    private String snapshotName;

    /** */
    @Parameter(example = "path", optional = true,
        description = "Path to the directory where the snapshot will be saved. " +
        "If not specified, the default configured snapshot directory will be used")
    private String dest;

    /** */
    @Parameter(optional = true, description = "Run the operation synchronously, " +
        "the command will wait for the entire operation to complete. " +
        "Otherwise, it will be performed in the background, and the command will immediately return control")
    private boolean sync;

    /** */
    @Parameter(optional = true, description = "Create an incremental snapshot for previously created full snapshot. " +
        "Full snapshot must be accessible via --dest and snapshot_name")
    private boolean incremental;

    /** {@inheritDoc} */
    @Override public String description() {
        return "Create cluster snapshot";
    }
}
