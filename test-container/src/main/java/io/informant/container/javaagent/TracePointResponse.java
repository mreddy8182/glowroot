/**
 * Copyright 2012-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.informant.container.javaagent;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.List;

import checkers.igj.quals.Immutable;
import checkers.nullness.quals.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Longs;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class TracePointResponse {

    private final List<RawPoint> normalPoints;
    private final List<RawPoint> errorPoints;
    private final List<RawPoint> activePoints;

    @JsonCreator
    TracePointResponse(@JsonProperty("normalPoints") List<RawPoint> normalPoints,
            @JsonProperty("errorPoints") List<RawPoint> errorPoints,
            @JsonProperty("activePoints") List<RawPoint> activePoints) {
        this.normalPoints = normalPoints;
        this.errorPoints = errorPoints;
        this.activePoints = activePoints;
    }

    List<RawPoint> getNormalPoints() {
        return normalPoints;
    }

    List<RawPoint> getErrorPoints() {
        return errorPoints;
    }

    List<RawPoint> getActivePoints() {
        return activePoints;
    }

    @Immutable
    static class RawPoint {

        static final Ordering<RawPoint> orderingByCapturedAt = new Ordering<RawPoint>() {
            @Override
            public int compare(@Nullable RawPoint left, @Nullable RawPoint right) {
                checkNotNull(left, "Ordering of non-null elements only");
                checkNotNull(right, "Ordering of non-null elements only");
                return Longs.compare(left.capturedAt, right.capturedAt);
            }
        };

        private final long capturedAt;
        private final double durationSeconds;
        private final String id;

        @JsonCreator
        RawPoint(ArrayNode point) {
            capturedAt = point.get(0).asLong();
            durationSeconds = point.get(1).asDouble();
            id = point.get(2).asText();
        }

        long getCapturedAt() {
            return capturedAt;
        }

        double getDurationSeconds() {
            return durationSeconds;
        }

        String getId() {
            return id;
        }
    }
}