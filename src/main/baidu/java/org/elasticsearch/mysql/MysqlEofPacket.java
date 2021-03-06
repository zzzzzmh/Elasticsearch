/*
 * Copyright (c) 2017 Baidu, Inc. All Rights Reserved.
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

package org.elasticsearch.mysql;

// MySQL protocol EOF packet
public class MysqlEofPacket extends MysqlPacket {
    private static final int EOF_INDICATOR = 0XFE;
    private static final int WARNINGS = 0;
    private static final int STATUS_FLAGS = 0;

    public MysqlEofPacket(QueryState state) {
    }

    @Override
    public void writeTo(MysqlSerializer serializer) {
        MysqlCapability capability = serializer.getCapability();

        serializer.writeInt1(EOF_INDICATOR);
        if (capability.isProtocol41()) {
            serializer.writeInt2(WARNINGS);
            serializer.writeInt2(STATUS_FLAGS);
        }
    }
}
