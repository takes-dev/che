/*******************************************************************************
 * Copyright (c) 2012-2017 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.api.factory.server;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonSyntaxException;
import com.jayway.restassured.http.ContentType;
import com.jayway.restassured.response.Response;

import org.eclipse.che.api.core.BadRequestException;
import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.model.factory.Factory;
import org.eclipse.che.api.core.model.project.ProjectConfig;
import org.eclipse.che.api.core.model.user.User;
import org.eclipse.che.api.core.model.workspace.WorkspaceConfig;
import org.eclipse.che.api.core.model.workspace.WorkspaceStatus;
import org.eclipse.che.api.core.rest.ApiExceptionMapper;
import org.eclipse.che.api.core.rest.shared.dto.ServiceError;
import org.eclipse.che.api.factory.server.FactoryService.FactoryParametersResolverHolder;
import org.eclipse.che.api.factory.server.builder.FactoryBuilder;
import org.eclipse.che.api.factory.server.impl.SourceStorageParametersValidator;
import org.eclipse.che.api.factory.server.model.impl.AuthorImpl;
import org.eclipse.che.api.factory.server.model.impl.FactoryImpl;
import org.eclipse.che.api.factory.shared.dto.FactoryDto;
import org.eclipse.che.api.machine.shared.dto.CommandDto;
import org.eclipse.che.api.user.server.PreferenceManager;
import org.eclipse.che.api.user.server.UserManager;
import org.eclipse.che.api.user.server.model.impl.UserImpl;
import org.eclipse.che.api.workspace.server.WorkspaceManager;
import org.eclipse.che.api.workspace.server.model.impl.EnvironmentImpl;
import org.eclipse.che.api.workspace.server.model.impl.EnvironmentRecipeImpl;
import org.eclipse.che.api.workspace.server.model.impl.ExtendedMachineImpl;
import org.eclipse.che.api.workspace.server.model.impl.ProjectConfigImpl;
import org.eclipse.che.api.workspace.server.model.impl.ServerConf2Impl;
import org.eclipse.che.api.workspace.server.model.impl.SourceStorageImpl;
import org.eclipse.che.api.workspace.server.model.impl.WorkspaceConfigImpl;
import org.eclipse.che.api.workspace.server.model.impl.WorkspaceImpl;
import org.eclipse.che.api.workspace.shared.dto.EnvironmentDto;
import org.eclipse.che.api.workspace.shared.dto.ExtendedMachineDto;
import org.eclipse.che.api.workspace.shared.dto.ProjectConfigDto;
import org.eclipse.che.api.workspace.shared.dto.SourceStorageDto;
import org.eclipse.che.commons.env.EnvironmentContext;
import org.eclipse.che.commons.json.JsonHelper;
import org.eclipse.che.commons.lang.Pair;
import org.eclipse.che.commons.subject.SubjectImpl;
import org.eclipse.che.dto.server.DtoFactory;
import org.everrest.assured.EverrestJetty;
import org.everrest.core.Filter;
import org.everrest.core.GenericContainerRequest;
import org.everrest.core.RequestFilter;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.testng.MockitoTestNGListener;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import javax.ws.rs.core.UriInfo;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jayway.restassured.RestAssured.given;
import static java.lang.String.format;
import static java.lang.String.valueOf;
import static java.lang.Thread.currentThread;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toSet;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static org.eclipse.che.api.factory.server.DtoConverter.asDto;
import static org.eclipse.che.api.factory.server.FactoryService.VALIDATE_QUERY_PARAMETER;
import static org.eclipse.che.dto.server.DtoFactory.cloneDto;
import static org.eclipse.che.dto.server.DtoFactory.newDto;
import static org.everrest.assured.JettyHttpServer.ADMIN_USER_NAME;
import static org.everrest.assured.JettyHttpServer.ADMIN_USER_PASSWORD;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyMapOf;
import static org.mockito.Matchers.anySetOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Tests for {@link FactoryService}.
 *
 * @author Anton Korneta
 */
@Listeners(value = {EverrestJetty.class, MockitoTestNGListener.class})
public class FactoryServiceTest {

    private static final String SERVICE_PATH            = "/factory";
    private static final String FACTORY_ID              = "correctFactoryId";
    private static final String FACTORY_NAME            = "factory";
    private static final String USER_ID                 = "userId";
    private static final String USER_EMAIL              = "email";
    private static final String WORKSPACE_NAME          = "workspace";
    private static final String PROJECT_SOURCE_TYPE     = "git";
    private static final String PROJECT_SOURCE_LOCATION = "https://github.com/codenvy/platform-api.git";
    private static final String FACTORY_IMAGE_MIME_TYPE = "image/jpeg";
    private static final String IMAGE_NAME              = "image12";


    private static final DtoFactory DTO = DtoFactory.getInstance();

    @Mock
    private FactoryManager                  factoryManager;
    @Mock
    private FactoryCreateValidator          createValidator;
    @Mock
    private FactoryAcceptValidator          acceptValidator;
    @Mock
    private PreferenceManager               preferenceManager;
    @Mock
    private UserManager                     userManager;
    @Mock
    private FactoryEditValidator            editValidator;
    @Mock
    private WorkspaceManager                workspaceManager;
    @Mock
    private FactoryParametersResolverHolder factoryParametersResolverHolder;
    @Mock
    private UriInfo                         uriInfo;

    private FactoryBuilder factoryBuilderSpy;

    private User                           user;
    private Set<FactoryParametersResolver> factoryParametersResolvers;

    private FactoryService service;

    @SuppressWarnings("unused")
    private ApiExceptionMapper apiExceptionMapper;
    @SuppressWarnings("unused")
    private EnvironmentFilter  environmentFilter;

    @BeforeMethod
    public void setUp() throws Exception {
        factoryBuilderSpy = spy(new FactoryBuilder(new SourceStorageParametersValidator()));
        factoryParametersResolvers = new HashSet<>();
        doNothing().when(factoryBuilderSpy).checkValid(any(FactoryDto.class));
        doNothing().when(factoryBuilderSpy).checkValid(any(FactoryDto.class), anyBoolean());
        when(factoryParametersResolverHolder.getFactoryParametersResolvers()).thenReturn(factoryParametersResolvers);
        user = new UserImpl(USER_ID, USER_EMAIL, ADMIN_USER_NAME);
        when(userManager.getById(anyString())).thenReturn(user);
        when(preferenceManager.find(USER_ID)).thenReturn(ImmutableMap.of("preference", "value"));
        service = new FactoryService(factoryManager,
                                     userManager,
                                     preferenceManager,
                                     createValidator,
                                     acceptValidator,
                                     editValidator,
                                     factoryBuilderSpy,
                                     workspaceManager,
                                     factoryParametersResolverHolder);
    }

    @Filter
    public static class EnvironmentFilter implements RequestFilter {
        public void doFilter(GenericContainerRequest request) {
            EnvironmentContext context = EnvironmentContext.getCurrent();
            context.setSubject(new SubjectImpl(ADMIN_USER_NAME, USER_ID, ADMIN_USER_PASSWORD, false));
        }
    }

    @Test
    public void shouldSaveFactoryWithImagesFromFormData() throws Exception {
        final Factory factory = createFactory();
        final FactoryDto factoryDto = asDto(factory, user);
        when(factoryManager.saveFactory(any(FactoryDto.class), anySetOf(FactoryImage.class))).thenReturn(factory);
        when(factoryManager.getById(FACTORY_ID)).thenReturn(factory);
        doReturn(factoryDto).when(factoryBuilderSpy).build(any(InputStream.class));

        final Response response = given().auth()
                                         .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                         .multiPart("factory", JsonHelper.toJson(factoryDto), APPLICATION_JSON)
                                         .multiPart("image", getImagePath().toFile(), FACTORY_IMAGE_MIME_TYPE)
                                         .expect()
                                         .statusCode(200)
                                         .when()
                                         .post(SERVICE_PATH);


        final FactoryDto result = getFromResponse(response, FactoryDto.class);
        final boolean found = result.getLinks()
                                    .stream()
                                    .anyMatch(link -> link.getRel().equals("image")
                                                      && link.getProduces().equals(FACTORY_IMAGE_MIME_TYPE)
                                                      && !link.getHref().isEmpty());
        factoryDto.withLinks(result.getLinks())
                  .getCreator()
                  .withCreated(result.getCreator().getCreated());
        assertEquals(result, factoryDto);
        assertTrue(found);
    }

    @Test
    public void shouldSaveFactoryFromFormDataWithoutImages() throws Exception {
        final Factory factory = createFactory();
        final FactoryDto factoryDto = asDto(factory, user);
        when(factoryManager.saveFactory(any(FactoryDto.class), anySetOf(FactoryImage.class))).thenReturn(factory);
        doReturn(factoryDto).when(factoryBuilderSpy).build(any(InputStream.class));

        final Response response = given().auth()
                                         .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                         .multiPart("factory", JsonHelper.toJson(factoryDto), APPLICATION_JSON)
                                         .expect()
                                         .statusCode(200)
                                         .when()
                                         .post(SERVICE_PATH);
        final FactoryDto result = getFromResponse(response, FactoryDto.class);
        factoryDto.withLinks(result.getLinks())
                  .getCreator()
                  .withCreated(result.getCreator().getCreated());
        assertEquals(result, factoryDto);
    }

    @Test
    public void shouldSaveFactoryWithImagesWhenImagesWithoutContent() throws Exception {
        final Factory factory = createFactory();
        when(factoryManager.saveFactory(any(FactoryDto.class), anySetOf(FactoryImage.class))).thenReturn(factory);
        when(factoryManager.getById(FACTORY_ID)).thenReturn(factory);
        final FactoryDto factoryDto = asDto(factory, user);
        doReturn(factoryDto).when(factoryBuilderSpy).build(any(InputStream.class));

        final Response response = given().auth()
                                         .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                         .multiPart("factory", DTO.toJson(factoryDto), APPLICATION_JSON)
                                         .multiPart("image", File.createTempFile("img", ".jpeg"), "image/jpeg")
                                         .expect()
                                         .statusCode(200)
                                         .when()
                                         .post(SERVICE_PATH);
        final FactoryDto result = getFromResponse(response, FactoryDto.class);
        verify(factoryManager).saveFactory(any(FactoryDto.class), anySetOf(FactoryImage.class));
        factoryDto.withLinks(result.getLinks())
                  .getCreator()
                  .withCreated(result.getCreator().getCreated());
        assertEquals(result, factoryDto);
    }

    @Test
    public void shouldThrowBadRequestExceptionWhenInvalidFactorySectionProvided() throws Exception {
        doThrow(new JsonSyntaxException("Invalid json")).when(factoryBuilderSpy).build(any(InputStream.class));
        final Response response = given().auth()
                                         .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                         .multiPart("factory", "invalid content", FACTORY_IMAGE_MIME_TYPE)
                                         .expect()
                                         .statusCode(400)
                                         .when()
                                         .post(SERVICE_PATH);

        final ServiceError err = getFromResponse(response, ServiceError.class);
        assertEquals(err.getMessage(), "Invalid JSON value of the field 'factory' provided");
    }

    @Test
    public void shouldThrowBadRequestExceptionWhenNoFactorySectionProvided() throws Exception {
        final Response response = given().auth()
                                         .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                         .multiPart("some data", "some content", FACTORY_IMAGE_MIME_TYPE)
                                         .expect()
                                         .statusCode(400)
                                         .when()
                                         .post(SERVICE_PATH);

        final ServiceError err = getFromResponse(response, ServiceError.class);
        assertEquals(err.getMessage(), "factory configuration required");
    }

    @Test
    public void shouldThrowServerExceptionWhenProvidedFactoryDataInvalid() throws Exception {
        final String errMessage = "eof";
        doThrow(new IOException(errMessage)).when(factoryBuilderSpy).build(any(InputStream.class));
        final Response response = given().auth()
                                         .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                         .multiPart("factory", "any content", FACTORY_IMAGE_MIME_TYPE)
                                         .expect()
                                         .statusCode(500)
                                         .when()
                                         .post(SERVICE_PATH);

        final ServiceError err = getFromResponse(response, ServiceError.class);
        assertEquals(err.getMessage(), errMessage);
    }

    @Test
    public void shouldSaveFactoryWithoutImages() throws Exception {
        final Factory factory = createFactory();
        final FactoryDto factoryDto = asDto(factory, user);
        when(factoryManager.saveFactory(any(FactoryDto.class))).thenReturn(factory);

        final Response response = given().auth()
                                         .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                         .contentType(ContentType.JSON)
                                         .body(factoryDto)
                                         .expect()
                                         .statusCode(200)
                                         .post(SERVICE_PATH);

        assertEquals(getFromResponse(response, FactoryDto.class).withLinks(emptyList()), factoryDto);
    }

    @Test
    public void shouldThrowBadRequestExceptionWhenFactoryConfigurationNotProvided() throws Exception {
        final Response response = given().auth()
                                         .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                         .contentType(ContentType.JSON)
                                         .expect()
                                         .statusCode(400)
                                         .post(SERVICE_PATH);
        final String errMessage = getFromResponse(response, ServiceError.class).getMessage();
        assertEquals(errMessage, "Factory configuration required");
    }

    @Test
    public void shouldReturnFactoryByIdentifierWithoutValidation() throws Exception {
        final Factory factory = createFactory();
        final FactoryDto factoryDto = asDto(factory, user);
        when(factoryManager.getById(FACTORY_ID)).thenReturn(factory);
        when(factoryManager.getFactoryImages(FACTORY_ID)).thenReturn(emptySet());

        final Response response = given().when()
                                         .expect()
                                         .statusCode(200)
                                         .get(SERVICE_PATH + "/" + FACTORY_ID);

        assertEquals(getFromResponse(response, FactoryDto.class).withLinks(emptyList()), factoryDto);
    }

    @Test
    public void shouldReturnFactoryByIdentifierWithValidation() throws Exception {
        final Factory factory = createFactory();
        final FactoryDto factoryDto = asDto(factory, user);
        when(factoryManager.getById(FACTORY_ID)).thenReturn(factory);
        when(factoryManager.getFactoryImages(FACTORY_ID)).thenReturn(emptySet());
        doNothing().when(acceptValidator).validateOnAccept(any(FactoryDto.class));

        final Response response = given().when()
                                         .expect()
                                         .statusCode(200)
                                         .get(SERVICE_PATH + "/" + FACTORY_ID + "?validate=true");

        assertEquals(getFromResponse(response, FactoryDto.class).withLinks(emptyList()), factoryDto);
    }

    @Test
    public void shouldThrowNotFoundExceptionWhenFactoryIsNotExist() throws Exception {
        final String errMessage = format("Factory with id %s is not found", FACTORY_ID);
        doThrow(new NotFoundException(errMessage)).when(factoryManager)
                                                  .getById(anyString());

        final Response response = given().expect()
                                         .statusCode(404)
                                         .when()
                                         .get(SERVICE_PATH + "/" + FACTORY_ID);

        assertEquals(getFromResponse(response, ServiceError.class).getMessage(), errMessage);
    }

    @Test
    public void shouldReturnFactoryListByNameAttribute() throws Exception {
        final Factory factory = createFactory();
        when(factoryManager.getByAttribute(1, 0, ImmutableList.of(Pair.of("factory.name", factory.getName()))))
                .thenReturn(ImmutableList.of(factory));

        final Response response = given().auth()
                                         .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                         .contentType(APPLICATION_JSON)
                                         .when()
                                         .expect()
                                         .statusCode(200)
                                         .get(SERVICE_PATH + "/find?maxItems=1&skipCount=0&factory.name=" + factory.getName());

        final List<FactoryDto> res = unwrapDtoList(response, FactoryDto.class);
        assertEquals(res.size(), 1);
        assertEquals(res.get(0).withLinks(emptyList()), asDto(factory, user));
    }

    @Test
    public void shouldReturnFactoryListByCreatorAttribute() throws Exception {
        final Factory factory1 = createNamedFactory("factory1");
        final Factory factory2 = createNamedFactory("factory2");
        when(factoryManager.getByAttribute(2, 0, ImmutableList.of(Pair.of("factory.creator.name", user.getName()))))
                .thenReturn(ImmutableList.of(factory1, factory2));

        final Response response = given().auth()
                                         .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                         .contentType(APPLICATION_JSON)
                                         .when()
                                         .expect()
                                         .statusCode(200)
                                         .get(SERVICE_PATH + "/find?maxItems=2&skipCount=0&factory.creator.name=" + user.getName());

        final Set<FactoryDto> res = unwrapDtoList(response, FactoryDto.class).stream()
                                                                             .map(f -> f.withLinks(emptyList()))
                                                                             .collect(toSet());
        assertEquals(res.size(), 2);
        assertTrue(res.containsAll(ImmutableList.of(asDto(factory1, user), asDto(factory2, user))));
    }

    @Test
    public void shouldThrowBadRequestWhenGettingFactoryByEmptyAttributeList() throws Exception {
        final Response response = given().auth()
                                         .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                         .contentType(APPLICATION_JSON)
                                         .when()
                                         .expect()
                                         .get(SERVICE_PATH + "/find?maxItems=1&skipCount=0");

        assertEquals(response.getStatusCode(), 400);
        assertEquals(DTO.createDtoFromJson(response.getBody().asString(), ServiceError.class)
                        .getMessage(), "Query must contain at least one attribute");
    }

    @Test
    public void shouldBeAbleToUpdateFactory() throws Exception {
        final Factory existed = createFactory();
        final Factory update = createFactoryWithStorage(null, "git", "https://github.com/codenvy/platform-api1.git");
        when(factoryManager.getById(FACTORY_ID)).thenReturn(existed);
        when(factoryManager.updateFactory(any())).thenReturn(update);

        final Response response = given().auth()
                                         .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                         .contentType(APPLICATION_JSON)
                                         .body(JsonHelper.toJson(asDto(existed, user)))
                                         .when()
                                         .expect()
                                         .statusCode(200)
                                         .put(SERVICE_PATH + "/" + FACTORY_ID);

        final FactoryDto result = getFromResponse(response, FactoryDto.class);
        verify(factoryManager, times(1)).updateFactory(any());
        assertEquals(result.withLinks(emptyList()), asDto(update, user));
    }

    @Test
    public void shouldThrowNotFoundExceptionWhenUpdatingNonExistingFactory() throws Exception {
        final Factory factory = createFactoryWithStorage(FACTORY_NAME,
                                                         "git",
                                                         "https://github.com/codenvy/platform-api.git");
        doThrow(new NotFoundException(format("Factory with id %s is not found.", FACTORY_ID))).when(factoryManager)
                                                                                              .getById(anyString());

        final Response response = given().auth().basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                         .contentType(APPLICATION_JSON)
                                         .body(JsonHelper.toJson(factory))
                                         .when()
                                         .put(SERVICE_PATH + "/" + FACTORY_ID);

        assertEquals(response.getStatusCode(), 404);
        assertEquals(DTO.createDtoFromJson(response.getBody().asString(), ServiceError.class).getMessage(),
                     format("Factory with id %s is not found.", FACTORY_ID));
    }

    @Test
    public void shouldNotBeAbleToUpdateANullFactory() throws Exception {
        final Response response = given().auth()
                                         .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                         .contentType(APPLICATION_JSON)
                                         .when()
                                         .put(SERVICE_PATH + "/" + FACTORY_ID);

        assertEquals(response.getStatusCode(), 400);
        assertEquals(DTO.createDtoFromJson(response.getBody().asString(), ServiceError.class)
                        .getMessage(), "Factory configuration required");
    }

    @Test
    public void shouldRemoveFactoryByGivenIdentifier() throws Exception {
        final Factory factory = createFactory();
        when(factoryManager.getById(FACTORY_ID)).thenReturn(factory);

        given().auth()
               .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
               .param("id", FACTORY_ID)
               .expect()
               .statusCode(204)
               .when()
               .delete(SERVICE_PATH + "/" + FACTORY_ID);

        verify(factoryManager).removeFactory(FACTORY_ID);
    }

    @Test
    public void shouldNotThrowAnyExceptionWhenRemovingNonExistingFactory() throws Exception {
        doNothing().when(factoryManager).removeFactory(anyString());

        Response response = given().auth()
                                   .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                   .param("id", FACTORY_ID)
                                   .when()
                                   .delete(SERVICE_PATH + "/" + FACTORY_ID);

        assertEquals(response.getStatusCode(), 204);
    }

    @Test
    public void shouldGenerateFactoryJsonIncludeGivenProjects() throws Exception {
        // given
        final String wsId = "workspace123234";
        WorkspaceImpl.WorkspaceImplBuilder ws = WorkspaceImpl.builder();
        WorkspaceConfigImpl.WorkspaceConfigImplBuilder wsConfig = WorkspaceConfigImpl.builder();
        ws.setId(wsId);
        wsConfig.setProjects(Arrays.asList(DTO.createDto(ProjectConfigDto.class)
                                              .withPath("/proj1")
                                              .withSource(DTO.createDto(SourceStorageDto.class)
                                                             .withType("git")
                                                             .withLocation("location")),
                                           DTO.createDto(ProjectConfigDto.class)
                                              .withPath("/proj2")
                                              .withSource(DTO.createDto(SourceStorageDto.class)
                                                             .withType("git")
                                                             .withLocation("location"))));
        wsConfig.setName("wsname");
        wsConfig.setEnvironments(singletonMap("env1", DTO.createDto(EnvironmentDto.class)));
        wsConfig.setDefaultEnv("env1");
        ws.setStatus(WorkspaceStatus.RUNNING);
        wsConfig.setCommands(singletonList(DTO.createDto(CommandDto.class)
                                              .withName("MCI")
                                              .withType("mvn")
                                              .withCommandLine("clean install")));
        ws.setConfig(wsConfig.build());
        WorkspaceImpl usersWorkspace = ws.build();
        when(workspaceManager.getWorkspace(eq(wsId))).thenReturn(usersWorkspace);

        // when
        Response response = given().auth()
                                   .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                   .when()
                                   .get("/private" + SERVICE_PATH + "/workspace/" + wsId);

        // then
        assertEquals(response.getStatusCode(), 200);
        FactoryDto result = DTO.createDtoFromJson(response.getBody().asString(), FactoryDto.class);
        assertEquals(result.getWorkspace().getProjects().size(), 2);
    }

    @Test
    public void shouldReturnFactoryImageWithGivenName() throws Exception {
        final byte[] imageContent = Files.readAllBytes(getImagePath());
        final FactoryImage image = new FactoryImage(imageContent, FACTORY_IMAGE_MIME_TYPE, IMAGE_NAME);
        when(factoryManager.getFactoryImages(FACTORY_ID, IMAGE_NAME)).thenReturn(ImmutableSet.of(image));

        final Response response = given().when()
                                         .expect()
                                         .statusCode(200)
                                         .get(SERVICE_PATH + "/" + FACTORY_ID + "/image?imgId=" + IMAGE_NAME);

        assertEquals(response.getContentType(), FACTORY_IMAGE_MIME_TYPE);
        assertEquals(response.getHeader("content-length"), String.valueOf(imageContent.length));
        assertEquals(response.asByteArray(), imageContent);
    }

    @Test
    public void shouldReturnFirstFoundFactoryImageWhenImageNameNotSpecified() throws Exception {
        final byte[] imageContent = Files.readAllBytes(getImagePath());
        final FactoryImage image = new FactoryImage(imageContent, FACTORY_IMAGE_MIME_TYPE, IMAGE_NAME);
        when(factoryManager.getFactoryImages(FACTORY_ID)).thenReturn(ImmutableSet.of(image));

        final Response response = given().when()
                                         .expect()
                                         .statusCode(200)
                                         .get(SERVICE_PATH + "/" + FACTORY_ID + "/image");

        assertEquals(response.getContentType(), "image/jpeg");
        assertEquals(response.getHeader("content-length"), String.valueOf(imageContent.length));
        assertEquals(response.asByteArray(), imageContent);
    }

    @Test
    public void shouldThrowNotFoundExceptionWhenFactoryImageWithGivenIdentifierIsNotExist() throws Exception {
        final String errMessage = "Image with name " + IMAGE_NAME + " is not found";
        when(factoryManager.getFactoryImages(FACTORY_ID, IMAGE_NAME)).thenThrow(new NotFoundException(errMessage));

        final Response response = given().expect()
                                         .statusCode(404)
                                         .when()
                                         .get(SERVICE_PATH + "/" + FACTORY_ID + "/image?imgId=" + IMAGE_NAME);

        assertEquals(getFromResponse(response, ServiceError.class).getMessage(), errMessage);
    }

    @Test
    public void shouldBeAbleToReturnUrlSnippet() throws Exception {
        final String result = "snippet";
        when(factoryManager.getFactorySnippet(anyString(), anyString(), any(URI.class))).thenReturn(result);

        given().expect()
               .statusCode(200)
               .contentType(TEXT_PLAIN)
               .body(equalTo(result))
               .when()
               .get(SERVICE_PATH + "/" + FACTORY_ID + "/snippet?type=url");
    }

    @Test
    public void shouldNotGenerateFactoryIfNoProjectsWithSourceStorage() throws Exception {
        // given
        final String wsId = "workspace123234";
        WorkspaceImpl.WorkspaceImplBuilder ws = WorkspaceImpl.builder();
        WorkspaceConfigImpl.WorkspaceConfigImplBuilder wsConfig = WorkspaceConfigImpl.builder();
        ws.setId(wsId);
        wsConfig.setProjects(Arrays.asList(DTO.createDto(ProjectConfigDto.class)
                                              .withPath("/proj1"),
                                           DTO.createDto(ProjectConfigDto.class)
                                              .withPath("/proj2")));
        wsConfig.setName("wsname");
        wsConfig.setEnvironments(singletonMap("env1", DTO.createDto(EnvironmentDto.class)));
        wsConfig.setDefaultEnv("env1");
        ws.setStatus(WorkspaceStatus.RUNNING);
        wsConfig.setCommands(singletonList(
                DTO.createDto(CommandDto.class).withName("MCI").withType("mvn").withCommandLine("clean install")));
        ws.setConfig(wsConfig.build());

        WorkspaceImpl usersWorkspace = ws.build();
        when(workspaceManager.getWorkspace(eq(wsId))).thenReturn(usersWorkspace);

        // when
        Response response = given().auth()
                                   .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                   .when()
                                   .get(SERVICE_PATH + "/workspace/" + wsId);

        // then
        assertEquals(response.getStatusCode(), BAD_REQUEST.getStatusCode());
    }

    @Test
    public void shouldResponse404OnGetSnippetIfFactoryDoesNotExist() throws Exception {
        // given
        doThrow(new NotFoundException("Factory URL with id " + FACTORY_ID + " is not found."))
                .when(factoryManager).getFactorySnippet(anyString(), anyString(), any());

        // when, then
        final Response response = given().expect()
                                         .statusCode(404)
                                         .when()
                                         .get(SERVICE_PATH + "/" + FACTORY_ID + "/snippet?type=url");

        assertEquals(getFromResponse(response, ServiceError.class).getMessage(),
                     "Factory URL with id " + FACTORY_ID + " is not found.");
    }

    /**
     * Checks that the user can remove an existing factory
     */
    @Test
    public void shouldBeAbleToRemoveFactory() throws Exception {
        final Factory factory = createFactory();
        when(factoryManager.getById(FACTORY_ID)).thenReturn(factory);

        final Response response = given().auth()
                                         .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                         .param("id", FACTORY_ID)
                                         .when()
                                         .delete(SERVICE_PATH + "/" + FACTORY_ID);

        assertEquals(response.getStatusCode(), 204);

        // check there was a call on the remove operation with expected ID
        verify(factoryManager).removeFactory(FACTORY_ID);
    }

    @Test
    public void shouldNotThrowExceptionWhenRemoveNoExistingFactory() throws Exception {
        doNothing().when(factoryManager).removeFactory(FACTORY_ID);

        final Response response = given().auth()
                                         .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                         .param("id", FACTORY_ID)
                                         .when()
                                         .delete(SERVICE_PATH + "/" + FACTORY_ID);

        assertEquals(response.getStatusCode(), 204);
    }

    @Test
    public void checkValidateResolver() throws Exception {
        final FactoryParametersResolver dummyResolver = Mockito.mock(FactoryParametersResolver.class);
        factoryParametersResolvers.add(dummyResolver);

        // invalid factory
        final String invalidFactoryMessage = "invalid factory";
        doThrow(new BadRequestException(invalidFactoryMessage)).when(acceptValidator).validateOnAccept(any());

        // create factory
        final FactoryDto expectFactory = DTO.createDto(FactoryDto.class).withV("4.0").withName("matchingResolverFactory");

        // accept resolver
        when(dummyResolver.accept(anyMapOf(String.class, String.class))).thenReturn(true);
        when(dummyResolver.createFactory(anyMapOf(String.class, String.class))).thenReturn(expectFactory);

        // when
        final Map<String, String> map = new HashMap<>();
        final Response response = given().contentType(ContentType.JSON)
                                         .when()
                                         .body(map)
                                         .queryParam(VALIDATE_QUERY_PARAMETER, valueOf(true))
                                         .post(SERVICE_PATH + "/resolver");

        // then check we have a not found
        assertEquals(response.getStatusCode(), BAD_REQUEST.getStatusCode());
        assertTrue(response.getBody().prettyPrint().contains(invalidFactoryMessage));

        // check we call resolvers
        verify(dummyResolver).accept(anyMapOf(String.class, String.class));
        verify(dummyResolver).createFactory(anyMapOf(String.class, String.class));

        // check we call validator
        verify(acceptValidator).validateOnAccept(any());
    }

    @Test
    public void shouldAddExecAgentOnSaveFactoryFromFormData() throws Exception {
        final Factory factory = createFactory();
        final FactoryDto factoryDto = asDto(factory, user);

        EnvironmentDto environment = newDto(EnvironmentDto.class);
        ExtendedMachineDto machine = newDto(ExtendedMachineDto.class);
        factoryDto.getWorkspace().setEnvironments(ImmutableMap.of("e1", cloneDto(environment).withMachines(
                ImmutableMap.of("m1", cloneDto(machine).withAgents(asList("org.eclipse.che.terminal",
                                                                          "org.eclipse.che.ls.php",
                                                                          "org.eclipse.che.ls.json")),
                                "m2", cloneDto(machine).withAgents(asList("org.eclipse.che.ls.php",
                                                                          "org.eclipse.che.terminal",
                                                                          "org.eclipse.che.ls.json")),
                                "m3", cloneDto(machine).withAgents(asList("org.eclipse.che.ls.php",
                                                                          "org.eclipse.che.ls.json")))
                                                                  ),
                                                                  "e2", cloneDto(environment).withMachines(
                        ImmutableMap.of("m4", cloneDto(machine).withAgents(asList("org.eclipse.che.terminal",
                                                                                  "org.eclipse.che.ls.php",
                                                                                  "org.eclipse.che.ls.json")),
                                        "m5", cloneDto(machine).withAgents(asList("org.eclipse.che.ls.php",
                                                                                  "org.eclipse.che.ls.json")))
                )));
        Map<String, EnvironmentDto> expectedEnvs = ImmutableMap.of("e1", cloneDto(environment).withMachines(
                ImmutableMap.of("m1", cloneDto(machine).withAgents(asList("org.eclipse.che.terminal",
                                                                          "org.eclipse.che.ls.php",
                                                                          "org.eclipse.che.ls.json",
                                                                          "org.eclipse.che.exec")),
                                "m2", cloneDto(machine).withAgents(asList("org.eclipse.che.ls.php",
                                                                          "org.eclipse.che.terminal",
                                                                          "org.eclipse.che.ls.json",
                                                                          "org.eclipse.che.exec")),
                                "m3", cloneDto(machine).withAgents(asList("org.eclipse.che.ls.php",
                                                                          "org.eclipse.che.ls.json")))
                                                                   ),
                                                                   "e2", cloneDto(environment).withMachines(
                        ImmutableMap.of("m4", cloneDto(machine).withAgents(asList("org.eclipse.che.terminal",
                                                                                  "org.eclipse.che.ls.php",
                                                                                  "org.eclipse.che.ls.json",
                                                                                  "org.eclipse.che.exec")),
                                        "m5", cloneDto(machine).withAgents(asList("org.eclipse.che.ls.php",
                                                                                  "org.eclipse.che.ls.json")))
                ));

        when(factoryManager.saveFactory(any(FactoryDto.class),
                                        anySetOf(FactoryImage.class)))
                .thenAnswer(invocation -> new FactoryImpl((Factory)invocation.getArguments()[0], null));
        doReturn(factoryDto).when(factoryBuilderSpy).build(any(InputStream.class));

        final Response response = given().auth()
                                         .basic(ADMIN_USER_NAME, ADMIN_USER_PASSWORD)
                                         .multiPart("factory", JsonHelper.toJson(factoryDto), APPLICATION_JSON)
//                                         .expect()
//                                         .statusCode(200)
                                         .when()
                                         .post(SERVICE_PATH);
        final FactoryDto result = getFromResponse(response, FactoryDto.class);
        Map<String, EnvironmentDto> actualEnvs = result.getWorkspace().getEnvironments();
        assertEquals(actualEnvs, expectedEnvs);
    }

    private Factory createFactory() {
        return createNamedFactory(FACTORY_NAME);
    }

    private Factory createNamedFactory(String name) {
        return createFactoryWithStorage(name, PROJECT_SOURCE_TYPE, PROJECT_SOURCE_LOCATION);
    }

    private Factory createFactoryWithStorage(String name, String type, String location) {
        return FactoryImpl.builder()
                          .setId(FACTORY_ID)
                          .setVersion("4.0")
                          .setWorkspace(createWorkspaceConfig(type, location))
                          .setCreator(new AuthorImpl(USER_ID, 12L))
                          .setName(name)
                          .build();
    }

    private static WorkspaceConfig createWorkspaceConfig(String type, String location) {
        return WorkspaceConfigImpl.builder()
                                  .setName(WORKSPACE_NAME)
                                  .setEnvironments(singletonMap("env1", new EnvironmentImpl(createEnvDto())))
                                  .setProjects(createProjects(type, location))
                                  .build();
    }

    private static EnvironmentDto createEnvDto() {
        final EnvironmentRecipeImpl environmentRecipe = new EnvironmentRecipeImpl();
        environmentRecipe.setType("type");
        environmentRecipe.setContent("content");
        environmentRecipe.setContentType("compose");
        environmentRecipe.setLocation("location");
        final EnvironmentImpl env = new EnvironmentImpl();
        final ExtendedMachineImpl extendedMachine = new ExtendedMachineImpl();
        extendedMachine.setAgents(singletonList("agent"));
        extendedMachine.setAttributes(singletonMap("att1", "value"));
        extendedMachine.setServers(singletonMap("agent", new ServerConf2Impl("5555",
                                                                             "https",
                                                                             singletonMap("prop1", "value1"))));
        env.setRecipe(environmentRecipe);
        env.setMachines(singletonMap("machine1", extendedMachine));
        return org.eclipse.che.api.workspace.server.DtoConverter.asDto(env);
    }

    private static List<ProjectConfig> createProjects(String type, String location) {
        final ProjectConfigImpl projectConfig = new ProjectConfigImpl();
        projectConfig.setSource(new SourceStorageImpl(type, location, null));
        return ImmutableList.of(projectConfig);
    }

    private static <T> T getFromResponse(Response response, Class<T> clazz) throws Exception {
        return DTO.createDtoFromJson(response.getBody().asInputStream(), clazz);
    }

    private static <T> List<T> unwrapDtoList(Response response, Class<T> dtoClass) {
        return FluentIterable.from(DtoFactory.getInstance().createListDtoFromJson(response.body().print(), dtoClass)).toList();
    }

    private static Path getImagePath() throws Exception {
        final URL res = currentThread().getContextClassLoader().getResource("100x100_image.jpeg");
        assertNotNull(res);
        return Paths.get(res.toURI());
    }
}
