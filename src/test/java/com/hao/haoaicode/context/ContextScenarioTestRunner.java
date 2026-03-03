package com.hao.haoaicode.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hao.haoaicode.constant.AppConstant;
import com.hao.haoaicode.manager.CosManager;
import com.hao.haoaicode.model.entity.User;
import com.hao.haoaicode.service.AppService;
import com.hao.haoaicode.service.UserService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SpringBootTest
public class ContextScenarioTestRunner {

    @Autowired
    private AppService appService;

    @Autowired
    private UserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CosManager cosManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    static class Scenario {
        public String name;
        public String description;
        public Long appId;
        public Long userId;
        public String codeGenType;
        public List<Round> rounds;
    }

    static class Round {
        public int round;
        public String prompt;
        public List<Assertion> assertions;
    }

    static class Assertion {
        public String type;
        public String path;
        public List<String> keywords;
        public Integer max;
        public List<String> paths;
    }

    @Test
    public void runAllScenarios() throws Exception {
        Path primaryDir = Paths.get("src", "test", "resources", "test_scenarios");
        Path secondaryDir = Paths.get("src", "test", "java", "com", "hao", "haoaicode", "resources", "test_scenarios");
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(primaryDir)) {
            try (Stream<Path> stream = Files.list(primaryDir)) {
                files.addAll(stream.filter(p -> p.toString().endsWith(".json")).collect(Collectors.toList()));
            }
        }
        if (Files.isDirectory(secondaryDir)) {
            try (Stream<Path> stream = Files.list(secondaryDir)) {
                files.addAll(stream.filter(p -> p.toString().endsWith(".json")).collect(Collectors.toList()));
            }
        }
        if (files.isEmpty()) {
            return;
        }
        int totalScenarios = 0;
        int totalRounds = 0;
        int totalAssertions = 0;
        for (Path path : files) {
            Scenario scenario = objectMapper.readValue(path.toFile(), Scenario.class);
            int scenarioRounds = scenario.rounds == null ? 0 : scenario.rounds.size();
            int scenarioAssertions = countAssertions(scenario);
            totalScenarios++;
            totalRounds += scenarioRounds;
            totalAssertions += scenarioAssertions;
            long start = System.currentTimeMillis();
            runScenario(scenario);
            long durationMs = System.currentTimeMillis() - start;
            System.out.println("[ContextSummary] scenario=" + scenario.name + ", rounds=" + scenarioRounds + ", assertions=" + scenarioAssertions + ", durationMs=" + durationMs);
        }
        System.out.println("[ContextSummary] totalScenarios=" + totalScenarios + ", totalRounds=" + totalRounds + ", totalAssertions=" + totalAssertions);
    }

    private void runScenario(Scenario scenario) throws Exception {
        Assertions.assertNotNull(scenario.appId);
        Assertions.assertNotNull(scenario.userId);
        Assertions.assertNotNull(scenario.codeGenType);
        User loginUser = userService.getById(scenario.userId);
        Assertions.assertNotNull(loginUser);
        if (scenario.rounds == null) {
            return;
        }
        for (Round round : scenario.rounds) {
            runRound(scenario, round, loginUser);
        }
    }

    private int countAssertions(Scenario scenario) {
        if (scenario == null || scenario.rounds == null) {
            return 0;
        }
        int count = 0;
        for (Round round : scenario.rounds) {
            if (round != null && round.assertions != null) {
                count += round.assertions.size();
            }
        }
        return count;
    }

    private void runRound(Scenario scenario, Round round, User loginUser) throws Exception {
        appService.chatToGenCode(scenario.appId, round.prompt, loginUser).collectList().block();
        File projectDir = resolveProjectDir(scenario);
        Assertions.assertTrue(projectDir.exists());
        if (round.assertions != null) {
            for (Assertion assertion : round.assertions) {
                applyAssertion(projectDir, assertion);
            }
        }
    }

    private File resolveProjectDir(Scenario scenario) throws Exception {
        String type = scenario.codeGenType == null ? "" : scenario.codeGenType.toLowerCase();
        if ("vue_project".equals(type)) {
            String key = stringRedisTemplate.opsForValue().get(String.format("code:source:latest:%d", scenario.appId));
            Assertions.assertNotNull(key);
            Path tempDir = Files.createTempDirectory("vue-project-" + scenario.appId);
            File dir = tempDir.toFile();
            boolean ok = cosManager.downloadDirectory(key, dir);
            Assertions.assertTrue(ok);
            return dir;
        }
        String sourceDirName = scenario.codeGenType + "_" + scenario.appId;
        String path = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName;
        return new File(path);
    }

    private void applyAssertion(File projectDir, Assertion assertion) throws Exception {
        if (assertion.type == null) {
            return;
        }
        String type = assertion.type.toLowerCase();
        switch (type) {
            case "build_success" -> assertBuildShape(projectDir);
            case "file_exists" -> {
                Assertions.assertNotNull(assertion.path);
                File f = new File(projectDir, assertion.path);
                Assertions.assertTrue(f.exists(), "file not exists: " + f.getAbsolutePath());
            }
            case "file_not_exists" -> {
                Assertions.assertNotNull(assertion.path);
                File f = new File(projectDir, assertion.path);
                Assertions.assertFalse(f.exists(), "file should not exist: " + f.getAbsolutePath());
            }
            case "file_contains_any" -> {
                Assertions.assertNotNull(assertion.path);
                List<String> keywords = assertion.keywords != null ? assertion.keywords : new ArrayList<>();
                Assertions.assertFalse(keywords.isEmpty());
                File f = new File(projectDir, assertion.path);
                Assertions.assertTrue(f.exists(), "file not exists: " + f.getAbsolutePath());
                String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                boolean matched = keywords.stream().anyMatch(text::contains);
                Assertions.assertTrue(matched, "none of keywords found in " + assertion.path);
            }
            case "file_contains_all" -> {
                Assertions.assertNotNull(assertion.path);
                List<String> keywords = assertion.keywords != null ? assertion.keywords : new ArrayList<>();
                Assertions.assertFalse(keywords.isEmpty());
                File f = new File(projectDir, assertion.path);
                Assertions.assertTrue(f.exists(), "file not exists: " + f.getAbsolutePath());
                String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                boolean matched = keywords.stream().allMatch(text::contains);
                Assertions.assertTrue(matched, "not all keywords found in " + assertion.path);
            }
            default -> {
            }
        }
    }

    private void assertBuildShape(File projectDir) {
        File packageJson = new File(projectDir, "package.json");
        Assertions.assertTrue(packageJson.exists(), "package.json not found");
        File srcDir = new File(projectDir, "src");
        Assertions.assertTrue(srcDir.exists(), "src dir not found");
    }
}

