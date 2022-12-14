package io.metersphere.api.jmeter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.metersphere.api.exec.queue.PoolExecBlockingQueueUtil;
import io.metersphere.api.service.ApiExecutionQueueService;
import io.metersphere.api.service.TestResultService;
import io.metersphere.commons.constants.ApiRunMode;
import io.metersphere.dto.ResultDTO;
import io.metersphere.utils.LoggerUtil;
import lombok.Data;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.*;

@Data
public class KafkaListenerTask implements Runnable {
    private List<ConsumerRecord<?, String>> records;
    private ApiExecutionQueueService apiExecutionQueueService;
    private TestResultService testResultService;
    private ObjectMapper mapper;

    private static final Map<String, String> RUN_MODE_MAP = new HashMap<String, String>() {{
        this.put(ApiRunMode.SCHEDULE_API_PLAN.name(), "schedule-task");
        this.put(ApiRunMode.JENKINS_API_PLAN.name(), "schedule-task");
        this.put(ApiRunMode.MANUAL_PLAN.name(), "schedule-task");

        this.put(ApiRunMode.DEFINITION.name(), "api-test-case-task");
        this.put(ApiRunMode.JENKINS.name(), "api-test-case-task");
        this.put(ApiRunMode.API_PLAN.name(), "api-test-case-task");
        this.put(ApiRunMode.JENKINS_API_PLAN.name(), "api-test-case-task");
        this.put(ApiRunMode.MANUAL_PLAN.name(), "api-test-case-task");


        this.put(ApiRunMode.SCENARIO.name(), "api-scenario-task");
        this.put(ApiRunMode.SCENARIO_PLAN.name(), "api-scenario-task");
        this.put(ApiRunMode.SCHEDULE_SCENARIO_PLAN.name(), "api-scenario-task");
        this.put(ApiRunMode.SCHEDULE_SCENARIO.name(), "api-scenario-task");
        this.put(ApiRunMode.JENKINS_SCENARIO_PLAN.name(), "api-scenario-task");

    }};

    @Override
    public void run() {
        try {
            LoggerUtil.info("??????KAFKA?????????????????????????????????????????????" + records.size());
            // ???????????????
            Map<String, List<ResultDTO>> assortMap = new LinkedHashMap<>();
            List<ResultDTO> resultDTOS = new LinkedList<>();

            records.forEach(record -> {
                ResultDTO testResult = this.formatResult(record.value());
                if (testResult != null) {
                    if (testResult.getArbitraryData() != null && testResult.getArbitraryData().containsKey("TEST_END") && (Boolean) testResult.getArbitraryData().get("TEST_END")) {
                        resultDTOS.add(testResult);
                    }
                    // ????????????
                    if (CollectionUtils.isNotEmpty(testResult.getRequestResults())) {
                        String key = RUN_MODE_MAP.get(testResult.getRunMode());
                        if (assortMap.containsKey(key)) {
                            assortMap.get(key).add(testResult);
                        } else {
                            assortMap.put(key, new LinkedList<ResultDTO>() {{
                                this.add(testResult);
                            }});
                        }
                    }
                }
            });

            if (MapUtils.isNotEmpty(assortMap)) {
                LoggerUtil.info("KAFKA??????????????????????????????");
                testResultService.batchSaveResults(assortMap);
                LoggerUtil.info("KAFKA??????????????????????????????");
            }
            // ??????????????????
            if (CollectionUtils.isNotEmpty(resultDTOS)) {
                resultDTOS.forEach(testResult -> {
                    LoggerUtil.info("?????? ??? " + testResult.getReportId() + " ????????? " + testResult.getTestId() + " ??????????????????");
                    testResultService.testEnded(testResult);
                    LoggerUtil.info("?????????????????????" + testResult.getQueueId());
                    apiExecutionQueueService.queueNext(testResult);
                    // ??????????????????
                    PoolExecBlockingQueueUtil.offer(testResult.getReportId());
                    // ????????????????????????
                    if (StringUtils.isNotEmpty(testResult.getTestPlanReportId())) {
                        LoggerUtil.info("Check Processing Test Plan report status???" + testResult.getQueueId() + "???" + testResult.getTestId());
                        apiExecutionQueueService.testPlanReportTestEnded(testResult.getTestPlanReportId());
                    }
                });
            }
        } catch (Exception e) {
            LoggerUtil.error("KAFKA???????????????", e);
        }
    }

    private ResultDTO formatResult(String result) {
        try {
            // ??????JSON?????????????????????????????????????????? ObjectMapper ??????
            if (StringUtils.isNotEmpty(result)) {
                return mapper.readValue(result, new TypeReference<ResultDTO>() {
                });
            }
        } catch (Exception e) {
            LoggerUtil.error("formatResult ????????????????????????", e);
        }
        return null;
    }
}
