/*
 * Copyright 2013 the original author or authors.
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
package io.informant.local.ui;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationTargetException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import javax.management.JMException;

import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Objects;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import com.google.common.primitives.Ints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.common.ObjectMappers;
import io.informant.config.WithVersionJsonView;
import io.informant.jvm.Availability;
import io.informant.jvm.Flags;
import io.informant.jvm.HeapHistograms;
import io.informant.jvm.HotSpotDiagnostic;
import io.informant.jvm.HotSpotDiagnostic.VMOption;
import io.informant.jvm.JDK6;
import io.informant.jvm.ProcessId;
import io.informant.jvm.ThreadAllocatedBytes;
import io.informant.jvm.ThreadContentionTime;
import io.informant.jvm.ThreadCpuTime;
import io.informant.markers.Singleton;

import static io.informant.common.Nullness.assertNonNull;

/**
 * Json service to read jvm info, bound to /backend/jvm.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@JsonService
class JvmJsonService {

    private static final Logger logger = LoggerFactory.getLogger(JvmJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final Ordering<ThreadInfo> orderingByStackSize = new Ordering<ThreadInfo>() {
        @Override
        public int compare(@Nullable ThreadInfo left, @Nullable ThreadInfo right) {
            assertNonNull(left, "Ordering of non-null elements only");
            assertNonNull(right, "Ordering of non-null elements only");
            return -Ints.compare(left.getStackTrace().length, right.getStackTrace().length);
        }
    };

    @JsonServiceMethod
    String getGeneralInfo() throws IOException, JMException {
        logger.debug("getGeneralInfo()");
        String pid = ProcessId.getPid();
        String command = System.getProperty("sun.java.command");
        String mainClass = null;
        List<String> arguments = ImmutableList.of();
        if (command != null) {
            int index = command.indexOf(' ');
            if (index > 0) {
                mainClass = command.substring(0, index);
                arguments = Lists
                        .newArrayList(Splitter.on(' ').split(command.substring(index + 1)));
            }
        }
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        String jvm = System.getProperty("java.vm.name") + " ("
                + System.getProperty("java.vm.version") + ", " + System.getProperty("java.vm.info")
                + ")";
        String java = "version " + System.getProperty("java.version") + ", vendor "
                + System.getProperty("java.vm.vendor");
        String javaHome = System.getProperty("java.home");

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(WithVersionJsonView.class);
        jg.writeStartObject();
        jg.writeNumberField("startTime", runtimeMXBean.getStartTime());
        jg.writeNumberField("uptime", runtimeMXBean.getUptime());
        jg.writeStringField("pid", Objects.firstNonNull(pid, "<unknown>"));
        jg.writeStringField("mainClass", mainClass);
        jg.writeFieldName("mainClassArguments");
        writer.writeValue(jg, arguments);
        jg.writeStringField("jvm", jvm);
        jg.writeStringField("java", java);
        jg.writeStringField("javaHome", javaHome);
        jg.writeFieldName("jvmArguments");
        writer.writeValue(jg, runtimeMXBean.getInputArguments());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String getSystemProperties() throws IOException {
        logger.debug("getSystemProperties()");
        Properties properties = System.getProperties();
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        for (Enumeration<?> e = properties.propertyNames(); e.hasMoreElements();) {
            Object obj = e.nextElement();
            if (obj instanceof String) {
                String propertyName = (String) obj;
                String propertyValue = properties.getProperty(propertyName);
                jg.writeStringField(propertyName, propertyValue);
            }
        }
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String getThreadDump() throws IOException {
        logger.debug("getThreadDump()");
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        long currentThreadId = Thread.currentThread().getId();
        List<ThreadInfo> threadInfos = Lists.newArrayList();
        for (long threadId : threadBean.getAllThreadIds()) {
            if (threadId != currentThreadId) {
                ThreadInfo threadInfo = threadBean.getThreadInfo(threadId, Integer.MAX_VALUE);
                if (threadInfo != null) {
                    threadInfos.add(threadInfo);
                }
            }
        }
        threadInfos = orderingByStackSize.sortedCopy(threadInfos);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartArray();
        for (ThreadInfo threadInfo : threadInfos) {
            jg.writeStartObject();
            jg.writeStringField("name", threadInfo.getThreadName());
            jg.writeStringField("state", threadInfo.getThreadState().name());
            jg.writeStringField("lockName", threadInfo.getLockName());
            jg.writeArrayFieldStart("stackTrace");
            for (StackTraceElement stackTraceElement : threadInfo.getStackTrace()) {
                jg.writeString(stackTraceElement.toString());
            }
            jg.writeEndArray();
            jg.writeEndObject();
        }
        jg.writeEndArray();
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String getHeapHistogram() throws IOException, SecurityException, NoSuchMethodException,
            IllegalAccessException, InvocationTargetException {
        logger.debug("getHeapHistogram()");
        return HeapHistograms.heapHistogramJson();
    }

    @JsonServiceMethod
    String getHeapDumpDefaults() throws IOException, JMException {
        logger.debug("getHeapDumpDefaults()");
        String heapDumpPath = HotSpotDiagnostic.getVMOption("HeapDumpPath").getValue();
        if (Strings.isNullOrEmpty(heapDumpPath)) {
            heapDumpPath = new File(System.getProperty("java.io.tmpdir")).getAbsolutePath();
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeStringField("directory", heapDumpPath);
        jg.writeBooleanField("checkDiskSpaceSupported", JDK6.isSupported());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String checkDiskSpace(String content) throws IOException, JMException,
            SecurityException, IllegalAccessException, InvocationTargetException {
        logger.debug("checkDiskSpace(): content={}", content);
        ObjectNode rootNode = (ObjectNode) mapper.readTree(content);
        String directory = rootNode.get("directory").asText();
        File dir = new File(directory);
        if (!dir.exists()) {
            return "{\"error\": \"Directory doesn't exist\"}";
        }
        if (!dir.isDirectory()) {
            return "{\"error\": \"Path is not a directory\"}";
        }
        long diskSpace = JDK6.getFreeSpace(new File(directory));
        return Long.toString(diskSpace);
    }

    @JsonServiceMethod
    String dumpHeap(String content) throws IOException, JMException {
        logger.debug("dumpHeap(): content={}", content);
        ObjectNode rootNode = (ObjectNode) mapper.readTree(content);
        String directory = rootNode.get("directory").asText();
        File dir = new File(directory);
        if (!dir.exists()) {
            return "{\"error\": \"Directory doesn't exist\"}";
        }
        if (!dir.isDirectory()) {
            return "{\"error\": \"Path is not a directory\"}";
        }
        String date = new SimpleDateFormat("yyyyMMdd").format(new Date());
        File file = new File(dir, "heapdump-" + date + ".hprof");
        int i = 1;
        while (file.exists()) {
            i++;
            file = new File(dir, "heapdump-" + date + "-" + i + ".hprof");
        }
        HotSpotDiagnostic.dumpHeap(file.getAbsolutePath());

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeStringField("filename", file.getAbsolutePath());
        jg.writeNumberField("size", file.length());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String getManageableFlags() throws IOException, JMException {
        logger.debug("getManageableFlags()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartArray();
        // don't sort these, there are not many and they seem to be sorted in meaningful order
        // already
        for (VMOption option : HotSpotDiagnostic.getDiagnosticOptions()) {
            // only handle true/false values for now
            if ("true".equals(option.getValue()) || "false".equals(option.getValue())) {
                // filter out seemingly less useful options (keep only HeapDump... and Print...)
                if (option.getName().startsWith("HeapDump")
                        || option.getName().startsWith("Print")) {
                    jg.writeStartObject();
                    jg.writeStringField("name", option.getName());
                    jg.writeBooleanField("value", Boolean.parseBoolean(option.getValue()));
                    jg.writeStringField("origin", option.getOrigin());
                    jg.writeEndObject();
                }
            }
        }
        jg.writeEndArray();
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String updateManageableFlags(String content) throws IOException, JMException {
        logger.debug("updateManageableFlags(): content={}", content);
        Map<String, Object> values =
                mapper.readValue(content, new TypeReference<Map<String, Object>>() {});
        for (Entry<String, Object> value : values.entrySet()) {
            HotSpotDiagnostic.setVMOption(value.getKey(), value.getValue().toString());
        }
        return getManageableFlags();
    }

    @JsonServiceMethod
    String getAllFlags() throws IOException, JMException, ClassNotFoundException,
            NoSuchMethodException, SecurityException, IllegalAccessException,
            InvocationTargetException {

        logger.debug("getAllFlags()");
        List<VMOption> options = Lists.newArrayList();
        for (String name : Flags.getFlagNames()) {
            options.add(HotSpotDiagnostic.getVMOption(name));
        }
        return mapper.writeValueAsString(VMOption.orderingByName.sortedCopy(options));
    }

    @JsonServiceMethod
    String getCapabilities() throws JsonGenerationException, IOException {
        logger.debug("getCapabilities()");
        Availability hotSpotDiagnosticAvailability = HotSpotDiagnostic.getAvailability();
        Availability allFlagsAvailability;
        if (!hotSpotDiagnosticAvailability.isAvailable()) {
            allFlagsAvailability = hotSpotDiagnosticAvailability;
        } else {
            Availability flagsAvailability = Flags.getAvailability();
            if (!flagsAvailability.isAvailable()) {
                allFlagsAvailability = flagsAvailability;
            } else {
                allFlagsAvailability = Availability.available();
            }
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeFieldName("threadCpuTime");
        mapper.writeValue(jg, ThreadCpuTime.getAvailability());
        jg.writeFieldName("threadContentionTime");
        mapper.writeValue(jg, ThreadContentionTime.getAvailability());
        jg.writeFieldName("threadAllocatedBytes");
        mapper.writeValue(jg, ThreadAllocatedBytes.getAvailability());
        jg.writeFieldName("heapHistogram");
        mapper.writeValue(jg, HeapHistograms.getAvailability());
        jg.writeFieldName("heapDump");
        mapper.writeValue(jg, hotSpotDiagnosticAvailability);
        jg.writeFieldName("manageableFlags");
        mapper.writeValue(jg, hotSpotDiagnosticAvailability);
        jg.writeFieldName("allFlags");
        mapper.writeValue(jg, allFlagsAvailability);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }
}