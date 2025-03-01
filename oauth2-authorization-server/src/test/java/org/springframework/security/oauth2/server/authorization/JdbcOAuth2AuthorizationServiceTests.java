/*
 * Copyright 2020-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.oauth2.server.authorization;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.OAuth2TokenType;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.TestRegisteredClients;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link JdbcOAuth2AuthorizationService}.
 *
 * @author Ovidiu Popa
 */
public class JdbcOAuth2AuthorizationServiceTests {
	private static final String OAUTH2_AUTHORIZATION_SCHEMA_SQL_RESOURCE = "org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql";
	private static final String CUSTOM_OAUTH2_AUTHORIZATION_SCHEMA_SQL_RESOURCE = "org/springframework/security/oauth2/server/authorization/custom-oauth2-authorization-schema.sql";
	private static final String OAUTH2_AUTHORIZATION_SCHEMA_CLOB_DATA_TYPE_SQL_RESOURCE = "org/springframework/security/oauth2/server/authorization/custom-oauth2-authorization-schema-clob-data-type.sql";
	private static final OAuth2TokenType AUTHORIZATION_CODE_TOKEN_TYPE = new OAuth2TokenType(OAuth2ParameterNames.CODE);
	private static final OAuth2TokenType STATE_TOKEN_TYPE = new OAuth2TokenType(OAuth2ParameterNames.STATE);
	private static final String ID = "id";
	private static final RegisteredClient REGISTERED_CLIENT = TestRegisteredClients.registeredClient().build();
	private static final String PRINCIPAL_NAME = "principal";
	private static final AuthorizationGrantType AUTHORIZATION_GRANT_TYPE = AuthorizationGrantType.AUTHORIZATION_CODE;
	private static final OAuth2AuthorizationCode AUTHORIZATION_CODE = new OAuth2AuthorizationCode(
			"code", Instant.now().truncatedTo(ChronoUnit.MILLIS), Instant.now().plus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS));

	private EmbeddedDatabase db;
	private JdbcOperations jdbcOperations;
	private RegisteredClientRepository registeredClientRepository;
	private JdbcOAuth2AuthorizationService authorizationService;

	@Before
	public void setUp() {
		this.db = createDb();
		this.jdbcOperations = new JdbcTemplate(this.db);
		this.registeredClientRepository = mock(RegisteredClientRepository.class);
		this.authorizationService = new JdbcOAuth2AuthorizationService(this.jdbcOperations, this.registeredClientRepository);
	}

	@After
	public void tearDown() {
		this.db.shutdown();
	}

	@Test
	public void constructorWhenJdbcOperationsIsNullThenThrowIllegalArgumentException() {
		// @formatter:off
		assertThatThrownBy(() -> new JdbcOAuth2AuthorizationService(null, this.registeredClientRepository))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("jdbcOperations cannot be null");
		// @formatter:on
	}

	@Test
	public void constructorWhenRegisteredClientRepositoryIsNullThenThrowIllegalArgumentException() {
		// @formatter:off
		assertThatThrownBy(() -> new JdbcOAuth2AuthorizationService(this.jdbcOperations, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("registeredClientRepository cannot be null");
		// @formatter:on
	}

	@Test
	public void constructorWhenLobHandlerIsNullThenThrowIllegalArgumentException() {
		// @formatter:off
		assertThatThrownBy(() -> new JdbcOAuth2AuthorizationService(this.jdbcOperations, this.registeredClientRepository, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("lobHandler cannot be null");
		// @formatter:on
	}

	@Test
	public void setAuthorizationRowMapperWhenNullThenThrowIllegalArgumentException() {
		// @formatter:off
		assertThatThrownBy(() -> this.authorizationService.setAuthorizationRowMapper(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("authorizationRowMapper cannot be null");
		// @formatter:on
	}

	@Test
	public void setAuthorizationParametersMapperWhenNullThenThrowIllegalArgumentException() {
		// @formatter:off
		assertThatThrownBy(() -> this.authorizationService.setAuthorizationParametersMapper(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("authorizationParametersMapper cannot be null");
		// @formatter:on
	}

	@Test
	public void saveWhenAuthorizationNullThenThrowIllegalArgumentException() {
		// @formatter:off
		assertThatThrownBy(() -> this.authorizationService.save(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("authorization cannot be null");
		// @formatter:on
	}

	@Test
	public void saveWhenAuthorizationNewThenSaved() {
		when(this.registeredClientRepository.findById(eq(REGISTERED_CLIENT.getId())))
				.thenReturn(REGISTERED_CLIENT);
		OAuth2Authorization expectedAuthorization = OAuth2Authorization.withRegisteredClient(REGISTERED_CLIENT)
				.id(ID)
				.principalName(PRINCIPAL_NAME)
				.authorizationGrantType(AUTHORIZATION_GRANT_TYPE)
				.token(AUTHORIZATION_CODE)
				.build();
		this.authorizationService.save(expectedAuthorization);

		OAuth2Authorization authorization = this.authorizationService.findById(ID);
		assertThat(authorization).isEqualTo(expectedAuthorization);
	}

	@Test
	public void saveWhenAuthorizationExistsThenUpdated() {
		when(this.registeredClientRepository.findById(eq(REGISTERED_CLIENT.getId())))
				.thenReturn(REGISTERED_CLIENT);
		OAuth2Authorization originalAuthorization = OAuth2Authorization.withRegisteredClient(REGISTERED_CLIENT)
				.id(ID)
				.principalName(PRINCIPAL_NAME)
				.authorizationGrantType(AUTHORIZATION_GRANT_TYPE)
				.token(AUTHORIZATION_CODE)
				.build();
		this.authorizationService.save(originalAuthorization);

		OAuth2Authorization authorization = this.authorizationService.findById(
				originalAuthorization.getId());
		assertThat(authorization).isEqualTo(originalAuthorization);

		OAuth2Authorization updatedAuthorization = OAuth2Authorization.from(authorization)
				.attribute("custom-name-1", "custom-value-1")
				.build();
		this.authorizationService.save(updatedAuthorization);

		authorization = this.authorizationService.findById(
				updatedAuthorization.getId());
		assertThat(authorization).isEqualTo(updatedAuthorization);
		assertThat(authorization).isNotEqualTo(originalAuthorization);
	}

	@Test
	public void saveLoadAuthorizationWhenCustomStrategiesSetThenCalled() throws Exception {
		when(this.registeredClientRepository.findById(eq(REGISTERED_CLIENT.getId())))
				.thenReturn(REGISTERED_CLIENT);
		OAuth2Authorization originalAuthorization = OAuth2Authorization.withRegisteredClient(REGISTERED_CLIENT)
				.id(ID)
				.principalName(PRINCIPAL_NAME)
				.authorizationGrantType(AUTHORIZATION_GRANT_TYPE)
				.token(AUTHORIZATION_CODE)
				.build();

		RowMapper<OAuth2Authorization> authorizationRowMapper = spy(
				new JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper(
						this.registeredClientRepository));
		this.authorizationService.setAuthorizationRowMapper(authorizationRowMapper);
		Function<OAuth2Authorization, List<SqlParameterValue>> authorizationParametersMapper = spy(
				new JdbcOAuth2AuthorizationService.OAuth2AuthorizationParametersMapper());
		this.authorizationService.setAuthorizationParametersMapper(authorizationParametersMapper);

		this.authorizationService.save(originalAuthorization);
		OAuth2Authorization authorization = this.authorizationService.findById(
				originalAuthorization.getId());
		assertThat(authorization).isEqualTo(originalAuthorization);
		verify(authorizationRowMapper).mapRow(any(), anyInt());
		verify(authorizationParametersMapper).apply(any());
	}

	@Test
	public void removeWhenAuthorizationNullThenThrowIllegalArgumentException() {
		// @formatter:off
		assertThatThrownBy(() -> this.authorizationService.remove(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("authorization cannot be null");
		// @formatter:on
	}

	@Test
	public void removeWhenAuthorizationProvidedThenRemoved() {
		when(this.registeredClientRepository.findById(eq(REGISTERED_CLIENT.getId())))
				.thenReturn(REGISTERED_CLIENT);
		OAuth2Authorization expectedAuthorization = OAuth2Authorization.withRegisteredClient(REGISTERED_CLIENT)
				.id(ID)
				.principalName(PRINCIPAL_NAME)
				.authorizationGrantType(AUTHORIZATION_GRANT_TYPE)
				.token(AUTHORIZATION_CODE)
				.build();

		this.authorizationService.save(expectedAuthorization);
		OAuth2Authorization authorization = this.authorizationService.findByToken(
				AUTHORIZATION_CODE.getTokenValue(), AUTHORIZATION_CODE_TOKEN_TYPE);
		assertThat(authorization).isEqualTo(expectedAuthorization);

		this.authorizationService.remove(authorization);
		authorization = this.authorizationService.findByToken(
				AUTHORIZATION_CODE.getTokenValue(), AUTHORIZATION_CODE_TOKEN_TYPE);
		assertThat(authorization).isNull();
	}

	@Test
	public void findByIdWhenIdNullThenThrowIllegalArgumentException() {
		// @formatter:off
		assertThatThrownBy(() -> this.authorizationService.findById(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("id cannot be empty");
		// @formatter:on
	}

	@Test
	public void findByIdWhenIdEmptyThenThrowIllegalArgumentException() {
		// @formatter:off
		assertThatThrownBy(() -> this.authorizationService.findById(" "))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("id cannot be empty");
		// @formatter:on
	}

	@Test
	public void findByTokenWhenTokenNullThenThrowIllegalArgumentException() {
		// @formatter:off
		assertThatThrownBy(() -> this.authorizationService.findByToken(null, AUTHORIZATION_CODE_TOKEN_TYPE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("token cannot be empty");
		// @formatter:on
	}

	@Test
	public void findByTokenWhenStateExistsThenFound() {
		when(this.registeredClientRepository.findById(eq(REGISTERED_CLIENT.getId())))
				.thenReturn(REGISTERED_CLIENT);
		String state = "state";
		OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(REGISTERED_CLIENT)
				.id(ID)
				.principalName(PRINCIPAL_NAME)
				.authorizationGrantType(AUTHORIZATION_GRANT_TYPE)
				.attribute(OAuth2ParameterNames.STATE, state)
				.build();
		this.authorizationService.save(authorization);

		OAuth2Authorization result = this.authorizationService.findByToken(
				state, STATE_TOKEN_TYPE);
		assertThat(authorization).isEqualTo(result);
		result = this.authorizationService.findByToken(state, null);
		assertThat(authorization).isEqualTo(result);
	}

	@Test
	public void findByTokenWhenAuthorizationCodeExistsThenFound() {
		when(this.registeredClientRepository.findById(eq(REGISTERED_CLIENT.getId())))
				.thenReturn(REGISTERED_CLIENT);
		OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(REGISTERED_CLIENT)
				.id(ID)
				.principalName(PRINCIPAL_NAME)
				.authorizationGrantType(AUTHORIZATION_GRANT_TYPE)
				.token(AUTHORIZATION_CODE)
				.build();
		this.authorizationService.save(authorization);

		OAuth2Authorization result = this.authorizationService.findByToken(
				AUTHORIZATION_CODE.getTokenValue(), AUTHORIZATION_CODE_TOKEN_TYPE);
		assertThat(authorization).isEqualTo(result);
		result = this.authorizationService.findByToken(AUTHORIZATION_CODE.getTokenValue(), null);
		assertThat(authorization).isEqualTo(result);
	}

	@Test
	public void findByTokenWhenAccessTokenExistsThenFound() {
		when(this.registeredClientRepository.findById(eq(REGISTERED_CLIENT.getId())))
				.thenReturn(REGISTERED_CLIENT);
		OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
				"access-token", Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS), Instant.now().truncatedTo(ChronoUnit.MILLIS));
		OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(REGISTERED_CLIENT)
				.id(ID)
				.principalName(PRINCIPAL_NAME)
				.authorizationGrantType(AUTHORIZATION_GRANT_TYPE)
				.token(AUTHORIZATION_CODE)
				.accessToken(accessToken)
				.build();
		this.authorizationService.save(authorization);

		OAuth2Authorization result = this.authorizationService.findByToken(
				accessToken.getTokenValue(), OAuth2TokenType.ACCESS_TOKEN);
		assertThat(authorization).isEqualTo(result);
		result = this.authorizationService.findByToken(accessToken.getTokenValue(), null);
		assertThat(authorization).isEqualTo(result);
	}

	@Test
	public void findByTokenWhenRefreshTokenExistsThenFound() {
		when(this.registeredClientRepository.findById(eq(REGISTERED_CLIENT.getId())))
				.thenReturn(REGISTERED_CLIENT);
		OAuth2RefreshToken refreshToken = new OAuth2RefreshToken("refresh-token",
				Instant.now().truncatedTo(ChronoUnit.MILLIS),
				Instant.now().plus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS));
		OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(REGISTERED_CLIENT)
				.id(ID)
				.principalName(PRINCIPAL_NAME)
				.authorizationGrantType(AUTHORIZATION_GRANT_TYPE)
				.refreshToken(refreshToken)
				.build();
		this.authorizationService.save(authorization);

		OAuth2Authorization result = this.authorizationService.findByToken(
				refreshToken.getTokenValue(), OAuth2TokenType.REFRESH_TOKEN);
		assertThat(authorization).isEqualTo(result);
		result = this.authorizationService.findByToken(refreshToken.getTokenValue(), null);
		assertThat(authorization).isEqualTo(result);
	}

	@Test
	public void findByTokenWhenWrongTokenTypeThenNotFound() {
		OAuth2RefreshToken refreshToken = new OAuth2RefreshToken("refresh-token", Instant.now().truncatedTo(ChronoUnit.MILLIS));
		OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(REGISTERED_CLIENT)
				.id(ID)
				.principalName(PRINCIPAL_NAME)
				.authorizationGrantType(AUTHORIZATION_GRANT_TYPE)
				.refreshToken(refreshToken)
				.build();
		this.authorizationService.save(authorization);

		OAuth2Authorization result = this.authorizationService.findByToken(
				refreshToken.getTokenValue(), OAuth2TokenType.ACCESS_TOKEN);
		assertThat(result).isNull();
	}

	@Test
	public void findByTokenWhenTokenDoesNotExistThenNull() {
		OAuth2Authorization result = this.authorizationService.findByToken(
				"access-token", OAuth2TokenType.ACCESS_TOKEN);
		assertThat(result).isNull();
	}

	@Test
	public void tableDefinitionWhenCustomThenAbleToOverride() {
		when(this.registeredClientRepository.findById(eq(REGISTERED_CLIENT.getId())))
				.thenReturn(REGISTERED_CLIENT);

		EmbeddedDatabase db = createDb(CUSTOM_OAUTH2_AUTHORIZATION_SCHEMA_SQL_RESOURCE);
		OAuth2AuthorizationService authorizationService =
				new CustomJdbcOAuth2AuthorizationService(new JdbcTemplate(db), this.registeredClientRepository);
		String state = "state";
		OAuth2Authorization originalAuthorization = OAuth2Authorization.withRegisteredClient(REGISTERED_CLIENT)
				.id(ID)
				.principalName(PRINCIPAL_NAME)
				.authorizationGrantType(AUTHORIZATION_GRANT_TYPE)
				.attribute(OAuth2ParameterNames.STATE, state)
				.token(AUTHORIZATION_CODE)
				.build();
		authorizationService.save(originalAuthorization);
		OAuth2Authorization foundAuthorization1 = authorizationService.findById(originalAuthorization.getId());
		assertThat(foundAuthorization1).isEqualTo(originalAuthorization);
		OAuth2Authorization foundAuthorization2 = authorizationService.findByToken(state, STATE_TOKEN_TYPE);
		assertThat(foundAuthorization2).isEqualTo(originalAuthorization);
		db.shutdown();
	}

	@Test
	public void tableDefinitionWhenClobSqlTypeThenAuthorizationUpdated() {
		when(this.registeredClientRepository.findById(eq(REGISTERED_CLIENT.getId())))
				.thenReturn(REGISTERED_CLIENT);

		EmbeddedDatabase db = createDb(OAUTH2_AUTHORIZATION_SCHEMA_CLOB_DATA_TYPE_SQL_RESOURCE);
		OAuth2AuthorizationService authorizationService =
				new JdbcOAuth2AuthorizationService(new JdbcTemplate(db), this.registeredClientRepository);
		OAuth2Authorization originalAuthorization = OAuth2Authorization.withRegisteredClient(REGISTERED_CLIENT)
				.id(ID)
				.principalName(PRINCIPAL_NAME)
				.authorizationGrantType(AUTHORIZATION_GRANT_TYPE)
				.token(AUTHORIZATION_CODE)
				.build();
		authorizationService.save(originalAuthorization);

		OAuth2Authorization authorization = authorizationService.findById(
				originalAuthorization.getId());
		assertThat(authorization).isEqualTo(originalAuthorization);

		OAuth2Authorization updatedAuthorization = OAuth2Authorization.from(authorization)
				.attribute("custom-name-1", "custom-value-1")
				.build();
		authorizationService.save(updatedAuthorization);

		authorization = authorizationService.findById(
				updatedAuthorization.getId());
		assertThat(authorization).isEqualTo(updatedAuthorization);
		assertThat(authorization).isNotEqualTo(originalAuthorization);
		db.shutdown();
	}

	private static EmbeddedDatabase createDb() {
		return createDb(OAUTH2_AUTHORIZATION_SCHEMA_SQL_RESOURCE);
	}

	private static EmbeddedDatabase createDb(String schema) {
		// @formatter:off
		return new EmbeddedDatabaseBuilder()
				.generateUniqueName(true)
				.setType(EmbeddedDatabaseType.HSQL)
				.setScriptEncoding("UTF-8")
				.addScript(schema)
				.build();
		// @formatter:on
	}

	private static final class CustomJdbcOAuth2AuthorizationService extends JdbcOAuth2AuthorizationService {

		// @formatter:off
		private static final String COLUMN_NAMES = "id, "
				+ "registeredClientId, "
				+ "principalName, "
				+ "authorizationGrantType, "
				+ "attributes, "
				+ "state, "
				+ "authorizationCodeValue, "
				+ "authorizationCodeIssuedAt, "
				+ "authorizationCodeExpiresAt,"
				+ "authorizationCodeMetadata,"
				+ "accessTokenValue,"
				+ "accessTokenIssuedAt,"
				+ "accessTokenExpiresAt,"
				+ "accessTokenMetadata,"
				+ "accessTokenType,"
				+ "accessTokenScopes,"
				+ "oidcIdTokenValue,"
				+ "oidcIdTokenIssuedAt,"
				+ "oidcIdTokenExpiresAt,"
				+ "oidcIdTokenMetadata,"
				+ "refreshTokenValue,"
				+ "refreshTokenIssuedAt,"
				+ "refreshTokenExpiresAt,"
				+ "refreshTokenMetadata";
		// @formatter:on

		private static final String TABLE_NAME = "oauth2Authorization";

		private static final String PK_FILTER = "id = ?";
		private static final String UNKNOWN_TOKEN_TYPE_FILTER = "state = ? OR authorizationCodeValue = ? OR " +
				"accessTokenValue = ? OR refreshTokenValue = ?";

		// @formatter:off
		private static final String LOAD_AUTHORIZATION_SQL = "SELECT " + COLUMN_NAMES
				+ " FROM " + TABLE_NAME
				+ " WHERE ";
		// @formatter:on

		// @formatter:off
		private static final String SAVE_AUTHORIZATION_SQL = "INSERT INTO " + TABLE_NAME
				+ " (" + COLUMN_NAMES + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
		// @formatter:on

		private static final String REMOVE_AUTHORIZATION_SQL = "DELETE FROM " + TABLE_NAME + " WHERE " + PK_FILTER;

		private CustomJdbcOAuth2AuthorizationService(JdbcOperations jdbcOperations,
				RegisteredClientRepository registeredClientRepository) {
			super(jdbcOperations, registeredClientRepository);
			setAuthorizationRowMapper(new CustomOAuth2AuthorizationRowMapper(registeredClientRepository));
			setAuthorizationParametersMapper(new CustomOAuth2AuthorizationParametersMapper());
		}

		@Override
		public void save(OAuth2Authorization authorization) {
			List<SqlParameterValue> parameters = getAuthorizationParametersMapper().apply(authorization);
			PreparedStatementSetter pss = new ArgumentPreparedStatementSetter(parameters.toArray());
			getJdbcOperations().update(SAVE_AUTHORIZATION_SQL, pss);
		}

		@Override
		public void remove(OAuth2Authorization authorization) {
			SqlParameterValue[] parameters = new SqlParameterValue[] {
					new SqlParameterValue(Types.VARCHAR, authorization.getId())
			};
			PreparedStatementSetter pss = new ArgumentPreparedStatementSetter(parameters);
			getJdbcOperations().update(REMOVE_AUTHORIZATION_SQL, pss);
		}

		@Override
		public OAuth2Authorization findById(String id) {
			return findBy(PK_FILTER, id);
		}

		@Override
		public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
			return findBy(UNKNOWN_TOKEN_TYPE_FILTER, token, token, token, token);
		}

		private OAuth2Authorization findBy(String filter, Object... args) {
			List<OAuth2Authorization> result = getJdbcOperations()
					.query(LOAD_AUTHORIZATION_SQL + filter, getAuthorizationRowMapper(), args);
			return !result.isEmpty() ? result.get(0) : null;
		}

		private static final class CustomOAuth2AuthorizationRowMapper extends JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper {

			private CustomOAuth2AuthorizationRowMapper(RegisteredClientRepository registeredClientRepository) {
				super(registeredClientRepository);
			}

			@Override
			@SuppressWarnings("unchecked")
			public OAuth2Authorization mapRow(ResultSet rs, int rowNum) throws SQLException {
				String registeredClientId = rs.getString("registeredClientId");
				RegisteredClient registeredClient = getRegisteredClientRepository().findById(registeredClientId);
				if (registeredClient == null) {
					throw new DataRetrievalFailureException(
							"The RegisteredClient with id '" + registeredClientId + "' was not found in the RegisteredClientRepository.");
				}

				OAuth2Authorization.Builder builder = OAuth2Authorization.withRegisteredClient(registeredClient);
				String id = rs.getString("id");
				String principalName = rs.getString("principalName");
				String authorizationGrantType = rs.getString("authorizationGrantType");
				Map<String, Object> attributes = parseMap(rs.getString("attributes"));

				builder.id(id)
						.principalName(principalName)
						.authorizationGrantType(new AuthorizationGrantType(authorizationGrantType))
						.attributes((attrs) -> attrs.putAll(attributes));

				String state = rs.getString("state");
				if (StringUtils.hasText(state)) {
					builder.attribute(OAuth2ParameterNames.STATE, state);
				}

				String tokenValue = rs.getString("authorizationCodeValue");
				Instant tokenIssuedAt;
				Instant tokenExpiresAt;
				if (tokenValue != null) {
					tokenIssuedAt = rs.getTimestamp("authorizationCodeIssuedAt").toInstant();
					tokenExpiresAt = rs.getTimestamp("authorizationCodeExpiresAt").toInstant();
					Map<String, Object> authorizationCodeMetadata = parseMap(rs.getString("authorizationCodeMetadata"));

					OAuth2AuthorizationCode authorizationCode = new OAuth2AuthorizationCode(
							tokenValue, tokenIssuedAt, tokenExpiresAt);
					builder.token(authorizationCode, (metadata) -> metadata.putAll(authorizationCodeMetadata));
				}

				tokenValue = rs.getString("accessTokenValue");
				if (tokenValue != null) {
					tokenIssuedAt = rs.getTimestamp("accessTokenIssuedAt").toInstant();
					tokenExpiresAt = rs.getTimestamp("accessTokenExpiresAt").toInstant();
					Map<String, Object> accessTokenMetadata = parseMap(rs.getString("accessTokenMetadata"));
					OAuth2AccessToken.TokenType tokenType = null;
					if (OAuth2AccessToken.TokenType.BEARER.getValue().equalsIgnoreCase(rs.getString("accessTokenType"))) {
						tokenType = OAuth2AccessToken.TokenType.BEARER;
					}

					Set<String> scopes = Collections.emptySet();
					String accessTokenScopes = rs.getString("accessTokenScopes");
					if (accessTokenScopes != null) {
						scopes = StringUtils.commaDelimitedListToSet(accessTokenScopes);
					}
					OAuth2AccessToken accessToken = new OAuth2AccessToken(tokenType, tokenValue, tokenIssuedAt, tokenExpiresAt, scopes);
					builder.token(accessToken, (metadata) -> metadata.putAll(accessTokenMetadata));
				}

				tokenValue = rs.getString("oidcIdTokenValue");
				if (tokenValue != null) {
					tokenIssuedAt = rs.getTimestamp("oidcIdTokenIssuedAt").toInstant();
					tokenExpiresAt = rs.getTimestamp("oidcIdTokenExpiresAt").toInstant();
					Map<String, Object> oidcTokenMetadata = parseMap(rs.getString("oidcIdTokenMetadata"));

					OidcIdToken oidcToken = new OidcIdToken(
							tokenValue, tokenIssuedAt, tokenExpiresAt, (Map<String, Object>) oidcTokenMetadata.get(OAuth2Authorization.Token.CLAIMS_METADATA_NAME));
					builder.token(oidcToken, (metadata) -> metadata.putAll(oidcTokenMetadata));
				}

				tokenValue = rs.getString("refreshTokenValue");
				if (tokenValue != null) {
					tokenIssuedAt = rs.getTimestamp("refreshTokenIssuedAt").toInstant();
					tokenExpiresAt = null;
					Timestamp refreshTokenExpiresAt = rs.getTimestamp("refreshTokenExpiresAt");
					if (refreshTokenExpiresAt != null) {
						tokenExpiresAt = refreshTokenExpiresAt.toInstant();
					}
					Map<String, Object> refreshTokenMetadata = parseMap(rs.getString("refreshTokenMetadata"));

					OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(
							tokenValue, tokenIssuedAt, tokenExpiresAt);
					builder.token(refreshToken, (metadata) -> metadata.putAll(refreshTokenMetadata));
				}

				return builder.build();
			}

			private Map<String, Object> parseMap(String data) {
				try {
					return getObjectMapper().readValue(data, new TypeReference<Map<String, Object>>() {});
				} catch (Exception ex) {
					throw new IllegalArgumentException(ex.getMessage(), ex);
				}
			}

		}

		private static final class CustomOAuth2AuthorizationParametersMapper extends JdbcOAuth2AuthorizationService.OAuth2AuthorizationParametersMapper {

			@Override
			public List<SqlParameterValue> apply(OAuth2Authorization authorization) {
				List<SqlParameterValue> parameters = new ArrayList<>();
				parameters.add(new SqlParameterValue(Types.VARCHAR, authorization.getId()));
				parameters.add(new SqlParameterValue(Types.VARCHAR, authorization.getRegisteredClientId()));
				parameters.add(new SqlParameterValue(Types.VARCHAR, authorization.getPrincipalName()));
				parameters.add(new SqlParameterValue(Types.VARCHAR, authorization.getAuthorizationGrantType().getValue()));

				String attributes = writeMap(authorization.getAttributes());
				parameters.add(new SqlParameterValue(Types.VARCHAR, attributes));

				String state = null;
				String authorizationState = authorization.getAttribute(OAuth2ParameterNames.STATE);
				if (StringUtils.hasText(authorizationState)) {
					state = authorizationState;
				}
				parameters.add(new SqlParameterValue(Types.VARCHAR, state));

				OAuth2Authorization.Token<OAuth2AuthorizationCode> authorizationCode =
						authorization.getToken(OAuth2AuthorizationCode.class);
				List<SqlParameterValue> authorizationCodeSqlParameters = toSqlParameterList(authorizationCode);
				parameters.addAll(authorizationCodeSqlParameters);

				OAuth2Authorization.Token<OAuth2AccessToken> accessToken =
						authorization.getToken(OAuth2AccessToken.class);
				List<SqlParameterValue> accessTokenSqlParameters = toSqlParameterList(accessToken);
				parameters.addAll(accessTokenSqlParameters);
				String accessTokenType = null;
				String accessTokenScopes = null;
				if (accessToken != null) {
					accessTokenType = accessToken.getToken().getTokenType().getValue();
					if (!CollectionUtils.isEmpty(accessToken.getToken().getScopes())) {
						accessTokenScopes = StringUtils.collectionToDelimitedString(accessToken.getToken().getScopes(), ",");
					}
				}
				parameters.add(new SqlParameterValue(Types.VARCHAR, accessTokenType));
				parameters.add(new SqlParameterValue(Types.VARCHAR, accessTokenScopes));

				OAuth2Authorization.Token<OidcIdToken> oidcIdToken = authorization.getToken(OidcIdToken.class);
				List<SqlParameterValue> oidcIdTokenSqlParameters = toSqlParameterList(oidcIdToken);
				parameters.addAll(oidcIdTokenSqlParameters);

				OAuth2Authorization.Token<OAuth2RefreshToken> refreshToken = authorization.getRefreshToken();
				List<SqlParameterValue> refreshTokenSqlParameters = toSqlParameterList(refreshToken);
				parameters.addAll(refreshTokenSqlParameters);
				return parameters;
			}

			private <T extends OAuth2Token> List<SqlParameterValue> toSqlParameterList(OAuth2Authorization.Token<T> token) {
				List<SqlParameterValue> parameters = new ArrayList<>();
				String tokenValue = null;
				Timestamp tokenIssuedAt = null;
				Timestamp tokenExpiresAt = null;
				String metadata = null;
				if (token != null) {
					tokenValue = token.getToken().getTokenValue();
					if (token.getToken().getIssuedAt() != null) {
						tokenIssuedAt = Timestamp.from(token.getToken().getIssuedAt());
					}
					if (token.getToken().getExpiresAt() != null) {
						tokenExpiresAt = Timestamp.from(token.getToken().getExpiresAt());
					}
					metadata = writeMap(token.getMetadata());
				}
				parameters.add(new SqlParameterValue(Types.VARCHAR, tokenValue));
				parameters.add(new SqlParameterValue(Types.TIMESTAMP, tokenIssuedAt));
				parameters.add(new SqlParameterValue(Types.TIMESTAMP, tokenExpiresAt));
				parameters.add(new SqlParameterValue(Types.VARCHAR, metadata));
				return parameters;
			}

			private String writeMap(Map<String, Object> data) {
				try {
					return getObjectMapper().writeValueAsString(data);
				} catch (Exception ex) {
					throw new IllegalArgumentException(ex.getMessage(), ex);
				}
			}

		}

	}

}
