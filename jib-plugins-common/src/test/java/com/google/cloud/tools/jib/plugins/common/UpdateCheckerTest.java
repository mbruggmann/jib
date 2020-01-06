/*
 * Copyright 2019 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.plugins.common;

import com.google.cloud.tools.jib.http.TestWebServer;
import com.google.cloud.tools.jib.json.JsonTemplateMapper;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.RestoreSystemProperties;
import org.junit.rules.TemporaryFolder;

/** Tests for {@link UpdateChecker} */
public class UpdateCheckerTest {

  @Rule public final RestoreSystemProperties systemPropertyRestorer = new RestoreSystemProperties();
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private TestWebServer testWebServer;
  private Path configDir;

  @Before
  public void setUp()
      throws InterruptedException, GeneralSecurityException, URISyntaxException, IOException {
    testWebServer =
        new TestWebServer(
            false, Collections.singletonList("HTTP/1.1 200 OK\nContent-Length:5\n\n2.0.0"), 1);
    configDir = temporaryFolder.newFolder().toPath();
  }

  @After
  public void tearDown() throws IOException {
    testWebServer.close();
  }

  @Test
  public void testPerformUpdateCheck_newVersionFound() throws IOException {
    Instant before = Instant.now();
    setupConfigAndLastUpdateCheck();
    Optional<String> message =
        UpdateChecker.performUpdateCheck(false, "1.0.2", testWebServer.getEndpoint(), configDir);
    Assert.assertTrue(message.isPresent());
    Assert.assertEquals(
        "A new version of Jib (2.0.0) is available (currently using 1.0.2). Update your build "
            + "configuration to use the latest features and fixes!",
        message.get());
    String modifiedTime =
        new String(
            Files.readAllBytes(configDir.resolve("lastUpdateCheck")), StandardCharsets.UTF_8);
    Assert.assertTrue(Instant.parse(modifiedTime).isAfter(before));
  }

  @Test
  public void testPerformUpdateCheck_onLatest() throws IOException {
    Instant before = Instant.now();
    setupConfigAndLastUpdateCheck();
    Optional<String> message =
        UpdateChecker.performUpdateCheck(false, "2.0.0", testWebServer.getEndpoint(), configDir);
    Assert.assertFalse(message.isPresent());
    String modifiedTime =
        new String(
            Files.readAllBytes(configDir.resolve("lastUpdateCheck")), StandardCharsets.UTF_8);
    Assert.assertTrue(Instant.parse(modifiedTime).isAfter(before));
  }

  @Test
  public void testPerformUpdateCheck_noConfigOrLastUpdateCheck() throws IOException {
    Instant before = Instant.now();
    Optional<String> message =
        UpdateChecker.performUpdateCheck(false, "1.0.2", testWebServer.getEndpoint(), configDir);
    Assert.assertTrue(message.isPresent());
    Assert.assertEquals(
        "A new version of Jib (2.0.0) is available (currently using 1.0.2). Update your build "
            + "configuration to use the latest features and fixes!",
        message.get());
    String modifiedTime =
        new String(
            Files.readAllBytes(configDir.resolve("lastUpdateCheck")), StandardCharsets.UTF_8);
    Assert.assertTrue(Instant.parse(modifiedTime).isAfter(before));
  }

  @Test
  public void testPerformUpdateCheck_lastUpdateCheckTooSoon() throws IOException {
    FileTime modifiedTime = FileTime.from(Instant.now().minusSeconds(12));
    setupConfigAndLastUpdateCheck();
    Files.write(
        configDir.resolve("lastUpdateCheck"),
        modifiedTime.toString().getBytes(StandardCharsets.UTF_8));
    Files.setLastModifiedTime(configDir.resolve("lastUpdateCheck"), modifiedTime);
    Optional<String> message =
        UpdateChecker.performUpdateCheck(false, "1.0.2", testWebServer.getEndpoint(), configDir);
    Assert.assertFalse(message.isPresent());

    // lastUpdateCheck should not have changed
    String lastUpdateTime =
        new String(
            Files.readAllBytes(configDir.resolve("lastUpdateCheck")), StandardCharsets.UTF_8);
    Assert.assertEquals(Instant.parse(lastUpdateTime), modifiedTime.toInstant());
  }

  @Test
  public void testPerformUpdateCheck_skip() {
    Optional<String> message =
        UpdateChecker.performUpdateCheck(true, "1.0.2", testWebServer.getEndpoint(), configDir);
    Assert.assertFalse(message.isPresent());
  }

  @Test
  public void testPerformUpdateCheck_systemProperty() {
    System.setProperty(PropertyNames.DISABLE_UPDATE_CHECKS, "true");
    Optional<String> message =
        UpdateChecker.performUpdateCheck(true, "1.0.2", testWebServer.getEndpoint(), configDir);
    Assert.assertFalse(message.isPresent());
  }

  @Test
  public void testPerformUpdateCheck_configDisabled() throws IOException {
    UpdateChecker.ConfigJsonTemplate config = new UpdateChecker.ConfigJsonTemplate();
    config.setDisableUpdateCheck(true);
    JsonTemplateMapper.writeTo(config, Files.newOutputStream(configDir.resolve("config.json")));

    Optional<String> message =
        UpdateChecker.performUpdateCheck(true, "1.0.2", testWebServer.getEndpoint(), configDir);
    Assert.assertFalse(message.isPresent());
  }

  @Test
  public void testPerformUpdateCheck_failSilently()
      throws InterruptedException, GeneralSecurityException, URISyntaxException, IOException {
    try (TestWebServer badServer = new TestWebServer(false, Collections.singletonList("bad"), 1)) {
      Optional<String> message =
          UpdateChecker.performUpdateCheck(true, "1.0.2", badServer.getEndpoint(), configDir);
      Assert.assertFalse(message.isPresent());
    }
  }

  @Test
  public void testFinishUpdateCheck_success() throws InterruptedException {
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    UpdateChecker updateChecker =
        new UpdateChecker(executorService.submit(() -> Optional.of("Hello")));
    Thread.sleep(100);
    Optional<String> result = updateChecker.finishUpdateCheck();
    Assert.assertTrue(result.isPresent());
    Assert.assertEquals("Hello", result.get());
  }

  @Test
  public void testFinishUpdateCheck_notDone() {
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    UpdateChecker updateChecker =
        new UpdateChecker(
            executorService.submit(
                () -> {
                  try {
                    Thread.sleep(100);
                  } catch (InterruptedException ignored) {
                  }
                  return Optional.of("Hello");
                }));
    Optional<String> result = updateChecker.finishUpdateCheck();
    Assert.assertFalse(result.isPresent());
  }

  private void setupConfigAndLastUpdateCheck() throws IOException {
    UpdateChecker.ConfigJsonTemplate config = new UpdateChecker.ConfigJsonTemplate();
    JsonTemplateMapper.writeTo(config, Files.newOutputStream(configDir.resolve("config.json")));
    Files.write(
        configDir.resolve("lastUpdateCheck"),
        Instant.now().minus(Duration.ofDays(2)).toString().getBytes(StandardCharsets.UTF_8));
  }
}
