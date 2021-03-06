/*
 * Modifications copyright (C) 2017, Baidu.com, Inc.
 * 
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.block;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.elasticsearch.cluster.AbstractDiffable;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaDataIndexStateService;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.rest.RestStatus;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Represents current cluster level blocks to block dirty operations done against the cluster.
 */
public class ClusterBlocks extends AbstractDiffable<ClusterBlocks> {

    public static final ClusterBlocks EMPTY_CLUSTER_BLOCK = new ClusterBlocks(ImmutableSet.<ClusterBlock>of(), ImmutableMap.<Long, ImmutableSet<ClusterBlock>>of(), ImmutableMap.<String, ImmutableSet<ClusterBlock>>of());

    public static final ClusterBlocks PROTO = EMPTY_CLUSTER_BLOCK;

    private final ImmutableSet<ClusterBlock> global;
    
    private final ImmutableMap<Long, ImmutableSet<ClusterBlock>> tenantsBlocks;

    private final ImmutableMap<String, ImmutableSet<ClusterBlock>> indicesBlocks;

    private final ImmutableLevelHolder[] levelHolders;

    ClusterBlocks(ImmutableSet<ClusterBlock> global, ImmutableMap<Long, ImmutableSet<ClusterBlock>> tenantsBlocks, ImmutableMap<String, ImmutableSet<ClusterBlock>> indicesBlocks) {
        this.global = global;
        this.tenantsBlocks = tenantsBlocks;
        this.indicesBlocks = indicesBlocks;

        levelHolders = new ImmutableLevelHolder[ClusterBlockLevel.values().length];
        for (ClusterBlockLevel level : ClusterBlockLevel.values()) {
            ImmutableSet.Builder<ClusterBlock> globalBuilder = ImmutableSet.builder();
            for (ClusterBlock block : global) {
                if (block.contains(level)) {
                    globalBuilder.add(block);
                }
            }
            
            ImmutableMap.Builder<Long, ImmutableSet<ClusterBlock>> tenantsBuilder = ImmutableMap.builder();
            for (Map.Entry<Long, ImmutableSet<ClusterBlock>> entry : tenantsBlocks.entrySet()) {
                ImmutableSet.Builder<ClusterBlock> tenantBuilder = ImmutableSet.builder();
                for (ClusterBlock block : entry.getValue()) {
                    if (block.contains(level)) {
                        tenantBuilder.add(block);
                    }
                }

                tenantsBuilder.put(entry.getKey(), tenantBuilder.build());
            }

            ImmutableMap.Builder<String, ImmutableSet<ClusterBlock>> indicesBuilder = ImmutableMap.builder();
            for (Map.Entry<String, ImmutableSet<ClusterBlock>> entry : indicesBlocks.entrySet()) {
                ImmutableSet.Builder<ClusterBlock> indexBuilder = ImmutableSet.builder();
                for (ClusterBlock block : entry.getValue()) {
                    if (block.contains(level)) {
                        indexBuilder.add(block);
                    }
                }

                indicesBuilder.put(entry.getKey(), indexBuilder.build());
            }

            levelHolders[level.id()] = new ImmutableLevelHolder(globalBuilder.build(), tenantsBuilder.build(), indicesBuilder.build());
        }
    }

    public ImmutableSet<ClusterBlock> global() {
        return global;
    }
    
    public ImmutableMap<Long, ImmutableSet<ClusterBlock>> tenants() {
        return tenantsBlocks;
    }
    
    public ImmutableMap<String, ImmutableSet<ClusterBlock>> indices() {
        return indicesBlocks;
    }

    public ImmutableSet<ClusterBlock> global(ClusterBlockLevel level) {
        return levelHolders[level.id()].global();
    }

    public ImmutableMap<Long, ImmutableSet<ClusterBlock>> tenants(ClusterBlockLevel level) {
        return levelHolders[level.id()].tenants();
    }
    
    public ImmutableMap<String, ImmutableSet<ClusterBlock>> indices(ClusterBlockLevel level) {
        return levelHolders[level.id()].indices();
    }

    /**
     * Returns <tt>true</tt> if one of the global blocks as its disable state persistence flag set.
     */
    public boolean disableStatePersistence() {
        for (ClusterBlock clusterBlock : global) {
            if (clusterBlock.disableStatePersistence()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasGlobalBlock(ClusterBlock block) {
        return global.contains(block);
    }

    public boolean hasGlobalBlock(int blockId) {
        for (ClusterBlock clusterBlock : global) {
            if (clusterBlock.id() == blockId) {
                return true;
            }
        }
        return false;
    }

    public boolean hasGlobalBlock(ClusterBlockLevel level) {
        return global(level).size() > 0;
    }

    /**
     * Is there a global block with the provided status?
     */
    public boolean hasGlobalBlock(RestStatus status) {
        for (ClusterBlock clusterBlock : global) {
            if (clusterBlock.status().equals(status)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasIndexBlock(String index, ClusterBlock block) {
        return indicesBlocks.containsKey(index) && indicesBlocks.get(index).contains(block);
    }

    public void globalBlockedRaiseException(ClusterBlockLevel level) throws ClusterBlockException {
        ClusterBlockException blockException = globalBlockedException(level);
        if (blockException != null) {
            throw blockException;
        }
    }

    public ClusterBlockException globalBlockedException(ClusterBlockLevel level) {
        if (global(level).isEmpty()) {
            return null;
        }
        return new ClusterBlockException(ImmutableSet.copyOf(global(level)));
    }
    
    public void tenantBlockedRaiseException(ClusterBlockLevel level, Long tenantId) throws ClusterBlockException {
        if (global(level).isEmpty()
                && (tenants(level).get(tenantId) == null 
                    || tenants(level).get(tenantId).isEmpty())) {
            return;
        }
        ImmutableSet.Builder<ClusterBlock> builder = ImmutableSet.builder();
        builder.addAll(global(level));
        ImmutableSet<ClusterBlock> tenantBlocks = tenants(level).get(tenantId);
        if (tenantBlocks != null) {
            builder.addAll(tenantBlocks);
        }
        throw new ClusterBlockException(builder.build());
    }
    
    public boolean hasTenantBlock(ClusterBlockLevel level, Long tenantId) {
        return tenants(level).get(tenantId) != null && !tenants(level).get(tenantId).isEmpty();
    }
    
    public boolean hasTenantBlock(ClusterBlock block, Long tenantId) {
        return tenantsBlocks.get(tenantId) != null && tenantsBlocks.get(tenantId).contains(block);
    }

    public void indexBlockedRaiseException(ClusterBlockLevel level, String index) throws ClusterBlockException {
        ClusterBlockException blockException = indexBlockedException(level, index);
        if (blockException != null) {
            throw blockException;
        }
    }

    public ClusterBlockException indexBlockedException(ClusterBlockLevel level, String index) {
        if (!indexBlocked(level, index)) {
            return null;
        }
        ImmutableSet.Builder<ClusterBlock> builder = ImmutableSet.builder();
        builder.addAll(global(level));
        ImmutableSet<ClusterBlock> indexBlocks = indices(level).get(index);
        if (indexBlocks != null) {
            builder.addAll(indexBlocks);
        }
        return new ClusterBlockException(builder.build());
    }

    public boolean indexBlocked(ClusterBlockLevel level, String index) {
        if (!global(level).isEmpty()) {
            return true;
        }
        ImmutableSet<ClusterBlock> indexBlocks = indices(level).get(index);
        if (indexBlocks != null && !indexBlocks.isEmpty()) {
            return true;
        }
        return false;
    }

    public ClusterBlockException indicesBlockedException(ClusterBlockLevel level, String[] indices) {
        boolean indexIsBlocked = false;
        for (String index : indices) {
            if (indexBlocked(level, index)) {
                indexIsBlocked = true;
            }
        }
        if (!indexIsBlocked) {
            return null;
        }
        ImmutableSet.Builder<ClusterBlock> builder = ImmutableSet.builder();
        builder.addAll(global(level));
        for (String index : indices) {
            ImmutableSet<ClusterBlock> indexBlocks = indices(level).get(index);
            if (indexBlocks != null) {
                builder.addAll(indexBlocks);
            }
        }
        return new ClusterBlockException(builder.build());
    }

    public String prettyPrint() {
        if (global.isEmpty() && tenants().isEmpty() && indices().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("blocks: \n");
        if (global.isEmpty() == false) {
            sb.append("   _global_:\n");
            for (ClusterBlock block : global) {
                sb.append("      ").append(block);
            }
        }
        for (Map.Entry<Long, ImmutableSet<ClusterBlock>> entry : tenantsBlocks.entrySet()) {
            sb.append("   ").append(entry.getKey()).append(":\n");
            for (ClusterBlock block : entry.getValue()) {
                sb.append("      ").append(block);
            }
        }
        sb.append("\n");
        for (Map.Entry<String, ImmutableSet<ClusterBlock>> entry : indicesBlocks.entrySet()) {
            sb.append("   ").append(entry.getKey()).append(":\n");
            for (ClusterBlock block : entry.getValue()) {
                sb.append("      ").append(block);
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        writeBlockSet(global, out);
        out.writeVInt(tenantsBlocks.size());
        for (Map.Entry<Long, ImmutableSet<ClusterBlock>> entry : tenantsBlocks.entrySet()) {
            out.writeLong(entry.getKey());
            writeBlockSet(entry.getValue(), out);
        }
        out.writeVInt(indicesBlocks.size());
        for (Map.Entry<String, ImmutableSet<ClusterBlock>> entry : indicesBlocks.entrySet()) {
            out.writeString(entry.getKey());
            writeBlockSet(entry.getValue(), out);
        }
    }

    private static void writeBlockSet(ImmutableSet<ClusterBlock> blocks, StreamOutput out) throws IOException {
        out.writeVInt(blocks.size());
        for (ClusterBlock block : blocks) {
            block.writeTo(out);
        }
    }

    @Override
    public ClusterBlocks readFrom(StreamInput in) throws IOException {
        ImmutableSet<ClusterBlock> global = readBlockSet(in);
        ImmutableMap.Builder<Long, ImmutableSet<ClusterBlock>> tenantsBuilder = ImmutableMap.builder();
        int tenantSize = in.readVInt();
        for (int j = 0; j < tenantSize; j++) {
            tenantsBuilder.put(in.readLong(), readBlockSet(in));
        }
        ImmutableMap.Builder<String, ImmutableSet<ClusterBlock>> indicesBuilder = ImmutableMap.builder();
        int size = in.readVInt();
        for (int j = 0; j < size; j++) {
            indicesBuilder.put(in.readString().intern(), readBlockSet(in));
        }
        return new ClusterBlocks(global, tenantsBuilder.build(), indicesBuilder.build());
    }

    private static ImmutableSet<ClusterBlock> readBlockSet(StreamInput in) throws IOException {
        ImmutableSet.Builder<ClusterBlock> builder = ImmutableSet.builder();
        int size = in.readVInt();
        for (int i = 0; i < size; i++) {
            builder.add(ClusterBlock.readClusterBlock(in));
        }
        return builder.build();
    }

    static class ImmutableLevelHolder {

        static final ImmutableLevelHolder EMPTY = new ImmutableLevelHolder(ImmutableSet.<ClusterBlock>of(), ImmutableMap.<Long, ImmutableSet<ClusterBlock>>of(), ImmutableMap.<String, ImmutableSet<ClusterBlock>>of());

        private final ImmutableSet<ClusterBlock> global;
        private final ImmutableMap<Long, ImmutableSet<ClusterBlock>> tenants;
        private final ImmutableMap<String, ImmutableSet<ClusterBlock>> indices;

        ImmutableLevelHolder(ImmutableSet<ClusterBlock> global, ImmutableMap<Long, ImmutableSet<ClusterBlock>> tenants, ImmutableMap<String, ImmutableSet<ClusterBlock>> indices) {
            this.global = global;
            this.tenants = tenants;
            this.indices = indices;
        }

        public ImmutableSet<ClusterBlock> global() {
            return global;
        }

        public ImmutableMap<Long, ImmutableSet<ClusterBlock>> tenants() {
            return tenants;
        }
        
        public ImmutableMap<String, ImmutableSet<ClusterBlock>> indices() {
            return indices;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private Set<ClusterBlock> global = Sets.newHashSet();

        private Map<Long, Set<ClusterBlock>> tenants = Maps.newHashMap();
        
        private Map<String, Set<ClusterBlock>> indices = Maps.newHashMap();

        public Builder() {
        }
        
        public Builder(ClusterBlocks blocks) {
            blocks(blocks);
        }

        public Builder blocks(ClusterBlocks blocks) {
            global.addAll(blocks.global());
            for (Map.Entry<Long, ImmutableSet<ClusterBlock>> entry : blocks.tenants().entrySet()) {
                if (!tenants.containsKey(entry.getKey())) {
                    tenants.put(entry.getKey(), Sets.<ClusterBlock>newHashSet());
                }
                tenants.get(entry.getKey()).addAll(entry.getValue());
            }
            for (Map.Entry<String, ImmutableSet<ClusterBlock>> entry : blocks.indices().entrySet()) {
                if (!indices.containsKey(entry.getKey())) {
                    indices.put(entry.getKey(), Sets.<ClusterBlock>newHashSet());
                }
                indices.get(entry.getKey()).addAll(entry.getValue());
            }
            return this;
        }

        public Builder addBlocks(IndexMetaData indexMetaData) {
            if (indexMetaData.getState() == IndexMetaData.State.CLOSE) {
                addIndexBlock(indexMetaData.getIndex(), MetaDataIndexStateService.INDEX_CLOSED_BLOCK);
            }
            if (indexMetaData.getSettings().getAsBoolean(IndexMetaData.SETTING_READ_ONLY, false)) {
                addIndexBlock(indexMetaData.getIndex(), IndexMetaData.INDEX_READ_ONLY_BLOCK);
            }
            if (indexMetaData.getSettings().getAsBoolean(IndexMetaData.SETTING_BLOCKS_READ, false)) {
                addIndexBlock(indexMetaData.getIndex(), IndexMetaData.INDEX_READ_BLOCK);
            }
            if (indexMetaData.getSettings().getAsBoolean(IndexMetaData.SETTING_BLOCKS_WRITE, false)) {
                addIndexBlock(indexMetaData.getIndex(), IndexMetaData.INDEX_WRITE_BLOCK);
            }
            if (indexMetaData.getSettings().getAsBoolean(IndexMetaData.SETTING_BLOCKS_METADATA, false)) {
                addIndexBlock(indexMetaData.getIndex(), IndexMetaData.INDEX_METADATA_BLOCK);
            }
            return this;
        }

        public Builder updateBlocks(IndexMetaData indexMetaData) {
            removeIndexBlock(indexMetaData.getIndex(), MetaDataIndexStateService.INDEX_CLOSED_BLOCK);
            removeIndexBlock(indexMetaData.getIndex(), IndexMetaData.INDEX_READ_ONLY_BLOCK);
            removeIndexBlock(indexMetaData.getIndex(), IndexMetaData.INDEX_READ_BLOCK);
            removeIndexBlock(indexMetaData.getIndex(), IndexMetaData.INDEX_WRITE_BLOCK);
            removeIndexBlock(indexMetaData.getIndex(), IndexMetaData.INDEX_METADATA_BLOCK);
            return addBlocks(indexMetaData);
        }

        public Builder addGlobalBlock(ClusterBlock block) {
            global.add(block);
            return this;
        }

        public Builder removeGlobalBlock(ClusterBlock block) {
            global.remove(block);
            return this;
        }

        public Builder addTenantBlock(Long tenantId, ClusterBlock block) {
            if (!tenants.containsKey(tenantId)) {
                tenants.put(tenantId, Sets.<ClusterBlock>newHashSet());
            }
            tenants.get(tenantId).add(block);
            return this;
        }
        
        public Builder removeTenantBlocks(Long tenantId) {
            if (!tenants.containsKey(tenantId)) {
                return this;
            }
            tenants.remove(tenantId);
            return this;
        }
        
        public Builder removeTenantBlock(Long tenantId, ClusterBlock block) {
            if (!tenants.containsKey(tenantId)) {
                return this;
            }
            tenants.get(tenantId).remove(block);
            if (tenants.get(tenantId).isEmpty()) {
                tenants.remove(tenantId);
            }
            return this;
        }
        
        public Builder addIndexBlock(String index, ClusterBlock block) {
            if (!indices.containsKey(index)) {
                indices.put(index, Sets.<ClusterBlock>newHashSet());
            }
            indices.get(index).add(block);
            return this;
        }

        public Builder removeIndexBlocks(String index) {
            if (!indices.containsKey(index)) {
                return this;
            }
            indices.remove(index);
            return this;
        }

        public Builder removeIndexBlock(String index, ClusterBlock block) {
            if (!indices.containsKey(index)) {
                return this;
            }
            indices.get(index).remove(block);
            if (indices.get(index).isEmpty()) {
                indices.remove(index);
            }
            return this;
        }

        public ClusterBlocks build() {
            ImmutableMap.Builder<String, ImmutableSet<ClusterBlock>> indicesBuilder = ImmutableMap.builder();
            for (Map.Entry<String, Set<ClusterBlock>> entry : indices.entrySet()) {
                indicesBuilder.put(entry.getKey(), ImmutableSet.copyOf(entry.getValue()));
            }
            ImmutableMap.Builder<Long, ImmutableSet<ClusterBlock>> tenantsBuilder = ImmutableMap.builder();
            for (Map.Entry<Long, Set<ClusterBlock>> entry : tenants.entrySet()) {
                tenantsBuilder.put(entry.getKey(), ImmutableSet.copyOf(entry.getValue()));
            }
            return new ClusterBlocks(ImmutableSet.copyOf(global), tenantsBuilder.build(), indicesBuilder.build());
        }

        public static ClusterBlocks readClusterBlocks(StreamInput in) throws IOException {
            return PROTO.readFrom(in);
        }
    }
}
