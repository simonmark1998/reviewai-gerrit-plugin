// Copyright (C) 2024 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.aicodereview;

import static com.googlesource.gerrit.plugins.aicodereview.config.Configuration.KEY_AI_CHAT_ENDPOINT;
import static com.googlesource.gerrit.plugins.aicodereview.config.Configuration.KEY_AI_DOMAIN;
import static com.googlesource.gerrit.plugins.aicodereview.config.Configuration.KEY_AI_TYPE;
import static com.googlesource.gerrit.plugins.aicodereview.config.Configuration.KEY_STREAM_OUTPUT;
import static com.googlesource.gerrit.plugins.aicodereview.utils.TextUtils.joinWithNewLine;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.google.common.net.HttpHeaders;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.Changes;
import com.google.gerrit.extensions.api.changes.FileApi;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.DiffInfo;
import com.google.gerrit.extensions.common.FileInfo;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.json.OutputFormat;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.aicodereview.listener.EventHandlerTask;
import com.googlesource.gerrit.plugins.aicodereview.listener.EventHandlerTask.SupportedEvents;
import com.googlesource.gerrit.plugins.aicodereview.mode.stateless.client.api.UriResourceLocatorStateless;
import com.googlesource.gerrit.plugins.aicodereview.mode.stateless.client.prompt.AIChatPromptStateless;
import com.googlesource.gerrit.plugins.aicodereview.settings.Settings;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.reflect.TypeLiteral;
import org.apache.http.entity.ContentType;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@Slf4j
@RunWith(MockitoJUnitRunner.class)
public class AIChatReviewStatelessTest extends AIChatReviewTestBase {
  private ReviewInput expectedResponseStreamed;
  private String expectedSystemPromptReview;
  private String promptTagReview;
  private String diffContent;
  private ReviewInput gerritPatchSetReview;
  private JsonArray prompts;

  private AIChatPromptStateless AIChatPromptStateless;

  protected void initConfig() {
    super.initGlobalAndProjectConfig();

    when(globalConfig.getBoolean(Mockito.eq(KEY_STREAM_OUTPUT), Mockito.anyBoolean()))
        .thenReturn(GPT_STREAM_OUTPUT);
    when(globalConfig.getBoolean(Mockito.eq("aiReviewCommitMessages"), Mockito.anyBoolean()))
        .thenReturn(true);

    super.initConfig();

    // Load the prompts
    AIChatPromptStateless = new AIChatPromptStateless(config);
  }

  protected void setupMockRequests() throws RestApiException {
    super.setupMockRequests();

    // Mock the behavior of the gerritPatchSetFiles request
    Map<String, FileInfo> files =
        readTestFileToType(
            "__files/stateless/gerritPatchSetFiles.json",
            new TypeLiteral<Map<String, FileInfo>>() {}.getType());
    when(revisionApiMock.files(0)).thenReturn(files);

    // Mock the behavior of the gerritPatchSet diff requests
    FileApi commitMsgFileMock = mock(FileApi.class);
    when(revisionApiMock.file("/COMMIT_MSG")).thenReturn(commitMsgFileMock);
    DiffInfo commitMsgFileDiff =
        readTestFileToClass("__files/stateless/gerritPatchSetDiffCommitMsg.json", DiffInfo.class);
    when(commitMsgFileMock.diff(0)).thenReturn(commitMsgFileDiff);
    FileApi testFileMock = mock(FileApi.class);
    when(revisionApiMock.file("test_file.py")).thenReturn(testFileMock);
    DiffInfo testFileDiff =
        readTestFileToClass("__files/stateless/gerritPatchSetDiffTestFile.json", DiffInfo.class);
    when(testFileMock.diff(0)).thenReturn(testFileDiff);

    // Mock the behavior of the askGpt request
    WireMock.stubFor(
        WireMock.post(
                WireMock.urlEqualTo(
                    URI.create(
                            config.getAIDomain() + UriResourceLocatorStateless.chatCompletionsUri())
                        .getPath()))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(HTTP_OK)
                    .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                    .withBodyFile("aiChatResponseStreamed.txt")));
  }

  protected void initComparisonContent() {
    super.initComparisonContent();

    diffContent = readTestFile("reducePatchSet/patchSetDiffOutput.json");
    gerritPatchSetReview =
        readTestFileToClass("__files/stateless/gerritPatchSetReview.json", ReviewInput.class);
    expectedResponseStreamed =
        readTestFileToClass(
            "__files/stateless/aiChatExpectedResponseStreamed.json", ReviewInput.class);
    promptTagReview = readTestFile("__files/stateless/aiChatPromptTagReview.json");
    promptTagComments = readTestFile("__files/stateless/aiChatPromptTagRequests.json");
    expectedSystemPromptReview = AIChatPromptStateless.getDefaultGptReviewSystemPrompt();
  }

  protected ArgumentCaptor<ReviewInput> testRequestSent() throws RestApiException {
    ArgumentCaptor<ReviewInput> reviewInputCaptor = super.testRequestSent();
    prompts = gptRequestBody.get("messages").getAsJsonArray();
    return reviewInputCaptor;
  }

  private String getReviewUserPrompt() {
    return joinWithNewLine(
        Arrays.asList(
            AIChatPromptStateless.DEFAULT_AI_CHAT_REVIEW_PROMPT,
            AIChatPromptStateless.DEFAULT_AI_CHAT_REVIEW_PROMPT_REVIEW
                + " "
                + AIChatPromptStateless.DEFAULT_AI_CHAT_PROMPT_FORCE_JSON_FORMAT
                + " "
                + AIChatPromptStateless.getPatchSetReviewPrompt(),
            AIChatPromptStateless.getReviewPromptCommitMessages(),
            AIChatPromptStateless.DEFAULT_AI_CHAT_REVIEW_PROMPT_DIFF,
            diffContent,
            AIChatPromptStateless.DEFAULT_AI_CHAT_REVIEW_PROMPT_MESSAGE_HISTORY,
            promptTagReview));
  }

  private void mockAzureAgentResponse(String responseBodyFile) {
    when(globalConfig.getString(Mockito.eq(KEY_AI_TYPE), Mockito.anyString()))
        .thenReturn("AZUREAGENT");
    when(globalConfig.getString(KEY_AI_DOMAIN)).thenReturn(GPT_DOMAIN);
    when(globalConfig.getString(Mockito.eq(KEY_AI_CHAT_ENDPOINT), Mockito.anyString()))
        .thenReturn("/api/projects/test/openai/v1");
    WireMock.stubFor(
        WireMock.post(WireMock.urlEqualTo("/api/projects/test/openai/v1/conversations"))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(HTTP_OK)
                    .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                    .withBodyFile("agentConversationResponse.json")));
    WireMock.stubFor(
        WireMock.post(WireMock.urlEqualTo("/api/projects/test/openai/v1/responses"))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(HTTP_OK)
                    .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                    .withBodyFile(responseBodyFile)));
  }

  private ReviewInput getSentReviewInput() throws RestApiException {
    ArgumentCaptor<ReviewInput> reviewInputCaptor = ArgumentCaptor.forClass(ReviewInput.class);
    verify(revisionApiMock).review(reviewInputCaptor.capture());
    return reviewInputCaptor.getValue();
  }

  @Test
  public void patchSetCreatedOrUpdatedStreamed() throws Exception {
    String reviewUserPrompt = getReviewUserPrompt();
    AIChatPromptStateless.setCommentEvent(false);

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    ArgumentCaptor<ReviewInput> captor = testRequestSent();
    String systemPrompt = prompts.get(0).getAsJsonObject().get("content").getAsString();
    Assert.assertEquals(expectedSystemPromptReview, systemPrompt);
    String userPrompt = prompts.get(1).getAsJsonObject().get("content").getAsString();
    Assert.assertEquals(reviewUserPrompt, userPrompt);

    Gson gson = OutputFormat.JSON_COMPACT.newGson();
    Assert.assertEquals(
        gson.toJson(expectedResponseStreamed), gson.toJson(captor.getAllValues().get(0)));
  }

  @Test
  public void patchSetCreatedOrUpdatedUnstreamed() throws Exception {
    when(globalConfig.getBoolean(Mockito.eq("aiStreamOutput"), Mockito.anyBoolean()))
        .thenReturn(false);
    when(globalConfig.getBoolean(Mockito.eq("enabledVoting"), Mockito.anyBoolean()))
        .thenReturn(true);

    String reviewUserPrompt = getReviewUserPrompt();
    AIChatPromptStateless.setCommentEvent(false);
    WireMock.stubFor(
        WireMock.post(
                WireMock.urlEqualTo(
                    URI.create(
                            config.getAIDomain() + UriResourceLocatorStateless.chatCompletionsUri())
                        .getPath()))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(HTTP_OK)
                    .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                    .withBodyFile("aiChatResponseReview.json")));

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    ArgumentCaptor<ReviewInput> captor = testRequestSent();
    String userPrompt = prompts.get(1).getAsJsonObject().get("content").getAsString();
    Assert.assertEquals(reviewUserPrompt, userPrompt);

    Gson gson = OutputFormat.JSON_COMPACT.newGson();
    Assert.assertEquals(
        gson.toJson(gerritPatchSetReview), gson.toJson(captor.getAllValues().get(0)));
  }

  /**
   * Verifies that when the AI response includes a {@code codeToken} field, the resulting inline
   * Gerrit comment highlights only that specific identifier rather than the full code snippet.
   *
   * <p>Test scenario: the AI returns {@code codeToken: "importclass"} for the line-21 comment.
   * Expected behaviour: the comment range narrows from the full-line span (start_character=4,
   * end_character=67) to the exact token span (start_character=20, end_character=31).
   */
  @Test
  public void patchSetCreatedCodeTokenPrecisionRange() throws Exception {
    when(globalConfig.getBoolean(Mockito.eq("aiStreamOutput"), Mockito.anyBoolean()))
        .thenReturn(false);
    when(globalConfig.getBoolean(Mockito.eq("enabledVoting"), Mockito.anyBoolean()))
        .thenReturn(true);

    AIChatPromptStateless.setCommentEvent(false);
    WireMock.stubFor(
        WireMock.post(
                WireMock.urlEqualTo(
                    URI.create(
                            config.getAIDomain() + UriResourceLocatorStateless.chatCompletionsUri())
                        .getPath()))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(HTTP_OK)
                    .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                    .withBodyFile("aiChatResponseWithCodeToken.json")));

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    ArgumentCaptor<ReviewInput> captor = testRequestSent();

    ReviewInput expectedReview =
        readTestFileToClass(
            "__files/stateless/gerritPatchSetReviewWithCodeToken.json", ReviewInput.class);
    Gson gson = OutputFormat.JSON_COMPACT.newGson();
    Assert.assertEquals(gson.toJson(expectedReview), gson.toJson(captor.getAllValues().get(0)));
  }

  @Test
  public void patchSetCreatedJsonMessageContentIsParsedIntoInlineComments() throws Exception {
    when(globalConfig.getBoolean(Mockito.eq("aiStreamOutput"), Mockito.anyBoolean()))
        .thenReturn(false);
    when(globalConfig.getBoolean(Mockito.eq("enabledVoting"), Mockito.anyBoolean()))
        .thenReturn(true);

    AIChatPromptStateless.setCommentEvent(false);
    WireMock.stubFor(
        WireMock.post(
                WireMock.urlEqualTo(
                    URI.create(
                            config.getAIDomain() + UriResourceLocatorStateless.chatCompletionsUri())
                        .getPath()))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(HTTP_OK)
                    .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                    .withBodyFile("aiChatResponseJsonContent.json")));

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    ArgumentCaptor<ReviewInput> captor = testRequestSent();

    ReviewInput expectedReview =
        readTestFileToClass(
            "__files/stateless/gerritPatchSetReviewJsonContent.json", ReviewInput.class);
    Gson gson = OutputFormat.JSON_COMPACT.newGson();
    Assert.assertEquals(gson.toJson(expectedReview), gson.toJson(captor.getAllValues().get(0)));
  }

  @Test
  public void patchSetCreatedTopicReplyIsSubmittedToTargetChange() throws Exception {
    when(globalConfig.getBoolean(Mockito.eq("aiStreamOutput"), Mockito.anyBoolean()))
        .thenReturn(false);
    when(globalConfig.getBoolean(Mockito.eq("enabledVoting"), Mockito.anyBoolean()))
        .thenReturn(true);

    String topic = "topic-review";
    String relatedChangeId = "myProject~myBranchName~relatedChangeId";
    ChangeInfo currentInfo =
        readTestFileToClass("__files/gerritPatchSetDetail.json", ChangeInfo.class);
    currentInfo.topic = topic;
    currentInfo.project = PROJECT_NAME.get();
    currentInfo.branch = BRANCH_NAME.shortName();
    currentInfo.changeId = CHANGE_ID.get();
    when(changeApiMock.get()).thenReturn(currentInfo);

    ChangeInfo relatedInfo = new ChangeInfo();
    relatedInfo.project = PROJECT_NAME.get();
    relatedInfo.branch = BRANCH_NAME.shortName();
    relatedInfo.changeId = "relatedChangeId";
    Changes.QueryRequest queryRequest = mock(Changes.QueryRequest.class);
    when(changesMock.query("topic:\"" + topic + "\" status:open")).thenReturn(queryRequest);
    when(queryRequest.get()).thenReturn(List.of(currentInfo, relatedInfo));

    Map<String, FileInfo> files =
        readTestFileToType(
            "__files/stateless/gerritPatchSetFiles.json",
            new TypeLiteral<Map<String, FileInfo>>() {}.getType());
    DiffInfo testFileDiff =
        readTestFileToClass("__files/stateless/gerritPatchSetDiffTestFile.json", DiffInfo.class);
    ChangeApi relatedChangeApi = mock(ChangeApi.class);
    RevisionApi relatedRevisionApi = mock(RevisionApi.class);
    FileApi relatedTestFileMock = mock(FileApi.class);
    when(changesMock.id(PROJECT_NAME.get(), BRANCH_NAME.shortName(), "relatedChangeId"))
        .thenReturn(relatedChangeApi);
    when(relatedChangeApi.current()).thenReturn(relatedRevisionApi);
    when(relatedRevisionApi.files(0)).thenReturn(Map.of("test_file.py", files.get("test_file.py")));
    when(relatedRevisionApi.file("test_file.py")).thenReturn(relatedTestFileMock);
    when(relatedTestFileMock.diff(0)).thenReturn(testFileDiff);
    when(relatedRevisionApi.review(Mockito.any(ReviewInput.class))).thenReturn(reviewResult);

    AIChatPromptStateless.setCommentEvent(false);
    WireMock.stubFor(
        WireMock.post(
                WireMock.urlEqualTo(
                    URI.create(
                            config.getAIDomain() + UriResourceLocatorStateless.chatCompletionsUri())
                        .getPath()))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(HTTP_OK)
                    .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                    .withBodyFile("aiChatResponseTopicRelatedJsonContent.json")));

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    Mockito.verify(revisionApiMock, Mockito.never()).review(Mockito.any(ReviewInput.class));
    ArgumentCaptor<ReviewInput> relatedReviewInputCaptor =
        ArgumentCaptor.forClass(ReviewInput.class);
    verify(relatedRevisionApi).review(relatedReviewInputCaptor.capture());
    ReviewInput reviewInput = relatedReviewInputCaptor.getValue();
    ReviewInput.CommentInput comment = reviewInput.comments.get("test_file.py").get(0);
    Assert.assertEquals("Topic-related change inline comment.", comment.message);
    Assert.assertEquals(Integer.valueOf(21), comment.line);
    Assert.assertNull(comment.range);
    Assert.assertEquals(Short.valueOf((short) -1), reviewInput.labels.get("Code-Review"));

    gptRequestBody =
        OutputFormat.JSON_COMPACT
            .newGson()
            .fromJson(patchSetReviewer.getChatAIClient().getRequestBody(), JsonObject.class);
    String userPrompt =
        gptRequestBody
            .get("messages")
            .getAsJsonArray()
            .get(1)
            .getAsJsonObject()
            .get("content")
            .getAsString();
    Assert.assertTrue(userPrompt.contains("\"changeId\":\"" + relatedChangeId + "\""));
    Assert.assertTrue(userPrompt.contains("\"files\""));
  }

  @Test
  public void patchSetCreatedAzureAgentNestedTextValueIsParsedIntoInlineComments()
      throws Exception {
    when(globalConfig.getBoolean(Mockito.eq("enabledVoting"), Mockito.anyBoolean()))
        .thenReturn(true);
    mockAzureAgentResponse("agentResponseNestedTextValueJson.json");

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    ReviewInput reviewInput = getSentReviewInput();
    ReviewInput.CommentInput comment = reviewInput.comments.get("test_file.py").get(0);
    Assert.assertEquals("Agent inline comment from nested text value.", comment.message);
    Assert.assertEquals(Integer.valueOf(21), comment.line);
    Assert.assertEquals(20, comment.range.startCharacter);
    Assert.assertEquals(31, comment.range.endCharacter);
    Assert.assertEquals(Short.valueOf((short) -1), reviewInput.labels.get("Code-Review"));
  }

  @Test
  public void patchSetCreatedAzureAgentFallsBackToLineCommentWhenSnippetDoesNotMatch()
      throws Exception {
    when(globalConfig.getBoolean(Mockito.eq("enabledVoting"), Mockito.anyBoolean()))
        .thenReturn(true);
    mockAzureAgentResponse("agentResponseLineOnlyJson.json");

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    ReviewInput reviewInput = getSentReviewInput();
    ReviewInput.CommentInput comment = reviewInput.comments.get("test_file.py").get(0);
    Assert.assertEquals("Agent line comment without an exact snippet match.", comment.message);
    Assert.assertEquals(Integer.valueOf(21), comment.line);
    Assert.assertNull(comment.range);
    Assert.assertEquals(Short.valueOf((short) -1), reviewInput.labels.get("Code-Review"));
  }

  @Test
  public void patchSetCreatedAzureAgentParsesReplyBodyPathAndLineAliases() throws Exception {
    when(globalConfig.getBoolean(Mockito.eq("enabledVoting"), Mockito.anyBoolean()))
        .thenReturn(true);
    mockAzureAgentResponse("agentResponseBodyPathLineJson.json");

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    ReviewInput reviewInput = getSentReviewInput();
    ReviewInput.CommentInput comment = reviewInput.comments.get("test_file.py").get(0);
    Assert.assertEquals("Agent comment from a body field.", comment.message);
    Assert.assertEquals(Integer.valueOf(21), comment.line);
    Assert.assertNull(comment.range);
    Assert.assertEquals(Short.valueOf((short) -1), reviewInput.labels.get("Code-Review"));
  }

  @Test
  public void patchSetCreatedAzureAgentParsesNestedLocationAndRangeAliases() throws Exception {
    when(globalConfig.getBoolean(Mockito.eq("enabledVoting"), Mockito.anyBoolean()))
        .thenReturn(true);
    mockAzureAgentResponse("agentResponseNestedLocationJson.json");

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    ReviewInput reviewInput = getSentReviewInput();
    ReviewInput.CommentInput comment = reviewInput.comments.get("test_file.py").get(0);
    Assert.assertEquals("Agent comment from nested location fields.", comment.message);
    Assert.assertEquals(Integer.valueOf(21), comment.line);
    Assert.assertEquals(20, comment.range.startCharacter);
    Assert.assertEquals(31, comment.range.endCharacter);
    Assert.assertEquals(Short.valueOf((short) -1), reviewInput.labels.get("Code-Review"));
  }

  @Test
  public void patchSetCreatedCommentsJsonMessageContentIsParsedIntoInlineComments()
      throws Exception {
    when(globalConfig.getBoolean(Mockito.eq("aiStreamOutput"), Mockito.anyBoolean()))
        .thenReturn(false);
    when(globalConfig.getBoolean(Mockito.eq("enabledVoting"), Mockito.anyBoolean()))
        .thenReturn(true);

    AIChatPromptStateless.setCommentEvent(false);
    WireMock.stubFor(
        WireMock.post(
                WireMock.urlEqualTo(
                    URI.create(
                            config.getAIDomain() + UriResourceLocatorStateless.chatCompletionsUri())
                        .getPath()))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(HTTP_OK)
                    .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                    .withBodyFile("aiChatResponseCommentsJsonContent.json")));

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    ReviewInput reviewInput = testRequestSent().getAllValues().get(0);
    ReviewInput.CommentInput comment = reviewInput.comments.get("test_file.py").get(0);
    Assert.assertEquals("Comment from comments JSON.", comment.message);
    Assert.assertEquals(Integer.valueOf(21), comment.line);
    Assert.assertEquals(20, comment.range.startCharacter);
    Assert.assertEquals(31, comment.range.endCharacter);
  }

  @Test
  public void patchSetCreatedArrayJsonMessageContentIsParsedIntoInlineComments() throws Exception {
    when(globalConfig.getBoolean(Mockito.eq("aiStreamOutput"), Mockito.anyBoolean()))
        .thenReturn(false);
    when(globalConfig.getBoolean(Mockito.eq("enabledVoting"), Mockito.anyBoolean()))
        .thenReturn(true);

    AIChatPromptStateless.setCommentEvent(false);
    WireMock.stubFor(
        WireMock.post(
                WireMock.urlEqualTo(
                    URI.create(
                            config.getAIDomain() + UriResourceLocatorStateless.chatCompletionsUri())
                        .getPath()))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(HTTP_OK)
                    .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                    .withBodyFile("aiChatResponseArrayJsonContent.json")));

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    ReviewInput reviewInput = testRequestSent().getAllValues().get(0);
    ReviewInput.CommentInput comment = reviewInput.comments.get("test_file.py").get(0);
    Assert.assertEquals("Comment from array JSON.", comment.message);
    Assert.assertEquals(Integer.valueOf(21), comment.line);
    Assert.assertEquals(20, comment.range.startCharacter);
    Assert.assertEquals(31, comment.range.endCharacter);
  }

  @Test
  public void patchSetCreatedPositiveJsonMessageContentSendsInlineCommentAndVote()
      throws Exception {
    when(globalConfig.getBoolean(Mockito.eq("aiStreamOutput"), Mockito.anyBoolean()))
        .thenReturn(false);
    when(globalConfig.getBoolean(Mockito.eq("enabledVoting"), Mockito.anyBoolean()))
        .thenReturn(true);

    AIChatPromptStateless.setCommentEvent(false);
    WireMock.stubFor(
        WireMock.post(
                WireMock.urlEqualTo(
                    URI.create(
                            config.getAIDomain() + UriResourceLocatorStateless.chatCompletionsUri())
                        .getPath()))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(HTTP_OK)
                    .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                    .withBodyFile("aiChatResponsePositiveJsonContent.json")));

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    ReviewInput reviewInput = testRequestSent().getAllValues().get(0);
    ReviewInput.CommentInput comment = reviewInput.comments.get("test_file.py").get(0);
    Assert.assertEquals("The change looks good.", comment.message);
    Assert.assertNull(reviewInput.message);
    Assert.assertEquals(Short.valueOf((short) 1), reviewInput.labels.get("Code-Review"));
  }

  @Test
  public void patchSetCreatedUnexpectedJsonMessageContentIsNotPostedRaw() throws Exception {
    when(globalConfig.getBoolean(Mockito.eq("aiStreamOutput"), Mockito.anyBoolean()))
        .thenReturn(false);
    when(globalConfig.getBoolean(Mockito.eq("enabledVoting"), Mockito.anyBoolean()))
        .thenReturn(true);

    AIChatPromptStateless.setCommentEvent(false);
    WireMock.stubFor(
        WireMock.post(
                WireMock.urlEqualTo(
                    URI.create(
                            config.getAIDomain() + UriResourceLocatorStateless.chatCompletionsUri())
                        .getPath()))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(HTTP_OK)
                    .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                    .withBodyFile("aiChatResponseUnexpectedJsonContent.json")));

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    ReviewInput reviewInput = testRequestSent().getAllValues().get(0);
    ReviewInput.CommentInput comment = reviewInput.comments.get("/PATCHSET_LEVEL").get(0);
    Assert.assertEquals(
        "this raw JSON must not be posted as a Gerrit review comment", comment.message);
    Assert.assertFalse(comment.message.contains("\"unexpected\""));
  }

  @Test
  public void patchSetDisableUserGroup() {
    when(globalConfig.getString(Mockito.eq("disabledGroups"), Mockito.anyString()))
        .thenReturn(GERRIT_USER_GROUP);

    Assert.assertEquals(
        EventHandlerTask.Result.NOT_SUPPORTED,
        handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED));
  }

  @Test
  public void gptMentionedInComment() throws RestApiException {
    when(config.getGerritUserName()).thenReturn(GERRIT_GPT_USERNAME);
    AIChatPromptStateless.setCommentEvent(true);
    WireMock.stubFor(
        WireMock.post(
                WireMock.urlEqualTo(
                    URI.create(
                            config.getAIDomain() + UriResourceLocatorStateless.chatCompletionsUri())
                        .getPath()))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(HTTP_OK)
                    .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                    .withBodyFile("aiChatResponseRequestStateless.json")));

    handleEventBasedOnType(SupportedEvents.COMMENT_ADDED);
    int commentPropertiesSize =
        gerritClient.getClientData(getGerritChange()).getCommentProperties().size();

    String commentUserPrompt =
        joinWithNewLine(
            Arrays.asList(
                AIChatPromptStateless.DEFAULT_AI_CHAT_REQUEST_PROMPT_DIFF,
                diffContent,
                AIChatPromptStateless.DEFAULT_AI_CHAT_REQUEST_PROMPT_REQUESTS,
                readTestFile("__files/stateless/aiChatExpectedRequestMessage.json"),
                AIChatPromptStateless.getCommentRequestPrompt(commentPropertiesSize)));
    testRequestSent();
    String userPrompt = prompts.get(1).getAsJsonObject().get("content").getAsString();
    Assert.assertEquals(commentUserPrompt, userPrompt);
  }

  @Test
  public void testAITypeValidOptions() {
    when(globalConfig.getString(Mockito.eq("aiType"), Mockito.anyString())).thenReturn("CHATGPT");

    // check default for aiType is chatGPT.
    Assert.assertEquals(config.getAIType(), Settings.AIType.CHATGPT);

    when(globalConfig.getString(Mockito.eq("aiType"), Mockito.anyString())).thenReturn("OLLAMA");

    Assert.assertEquals(config.getAIType(), Settings.AIType.OLLAMA);
  }

  @Test
  public void testAITypeControlsEndpoint() {
    when(globalConfig.getString(Mockito.eq("aiType"), Mockito.anyString())).thenReturn("CHATGPT");

    // check default for aiType is chatGPT.
    Assert.assertEquals(config.getChatEndpoint(), "");
    Assert.assertEquals(
        UriResourceLocatorStateless.chatCompletionsUri(),
        UriResourceLocatorStateless.getChatResourceUri(config));

    // swap it to ollama, check we still get the chatCompletionsUri, as its the openai
    // compat endpoint we use.
    when(globalConfig.getString(Mockito.eq("aiType"), Mockito.anyString())).thenReturn("OLLAMA");
    Assert.assertEquals(
        UriResourceLocatorStateless.chatCompletionsUri(),
        UriResourceLocatorStateless.getChatResourceUri(config));

    // finally change to GENERIC, and check that we can specify any endpoint
    when(globalConfig.getString(Mockito.eq(KEY_AI_TYPE), Mockito.anyString()))
        .thenReturn("GENERIC");

    final String expectedValueForEndpoint = "/someendpoint/someapi/chat";
    when(globalConfig.getString(Mockito.eq(KEY_AI_CHAT_ENDPOINT), Mockito.anyString()))
        .thenReturn(expectedValueForEndpoint);
    Assert.assertEquals(
        expectedValueForEndpoint, UriResourceLocatorStateless.getChatResourceUri(config));
  }

  @Test
  public void testAITypeControlsAuthHeader() {
    when(globalConfig.getString(Mockito.eq("aiType"), Mockito.anyString())).thenReturn("CHATGPT");

    // check default for aiType is chatGPT.
    Assert.assertEquals("Authorization", config.getAuthorizationHeaderInfo().getName());
    Assert.assertEquals(
        "Bearer " + config.getAIToken(), config.getAuthorizationHeaderInfo().getValue());

    // swap it to ollama, check we still get the chatCompletionsUri, as its the openai
    // compat endpoint we use.
    when(globalConfig.getString(Mockito.eq("aiType"), Mockito.anyString())).thenReturn("OLLAMA");
    Assert.assertNull(
        "No expected value for auth header for ollama", config.getAuthorizationHeaderInfo());
  }
}
