/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
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
package org.wildfly.security.http.impl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.wildfly.security._private.ElytronMessages.httpDigest;
import static org.wildfly.security.http.HttpConstants.ALGORITHM;
import static org.wildfly.security.http.HttpConstants.AUTH;
import static org.wildfly.security.http.HttpConstants.AUTHORIZATION;
import static org.wildfly.security.http.HttpConstants.CNONCE;
import static org.wildfly.security.http.HttpConstants.DIGEST_NAME;
import static org.wildfly.security.http.HttpConstants.NC;
import static org.wildfly.security.http.HttpConstants.QOP;
import static org.wildfly.security.http.HttpConstants.URI;
import static org.wildfly.security.http.HttpConstants.DOMAIN;
import static org.wildfly.security.http.HttpConstants.MD5;
import static org.wildfly.security.http.HttpConstants.NONCE;
import static org.wildfly.security.http.HttpConstants.OPAQUE;
import static org.wildfly.security.http.HttpConstants.REALM;
import static org.wildfly.security.http.HttpConstants.RESPONSE;
import static org.wildfly.security.http.HttpConstants.STALE;
import static org.wildfly.security.http.HttpConstants.UNAUTHORIZED;
import static org.wildfly.security.http.HttpConstants.USERNAME;
import static org.wildfly.security.http.HttpConstants.WWW_AUTHENTICATE;
import static org.wildfly.security.mechanism.digest.DigestUtil.parseResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.AuthorizeCallback;

import org.wildfly.security.auth.callback.AuthenticationCompleteCallback;
import org.wildfly.security.auth.callback.AvailableRealmsCallback;
import org.wildfly.security.http.HttpAuthenticationException;
import org.wildfly.security.http.HttpConstants;
import org.wildfly.security.http.HttpServerAuthenticationMechanism;
import org.wildfly.security.http.HttpServerMechanismsResponder;
import org.wildfly.security.http.HttpServerRequest;
import org.wildfly.security.http.HttpServerResponse;
import org.wildfly.security.mechanism.AuthenticationMechanismException;
import org.wildfly.security.mechanism.digest.DigestQuote;
import org.wildfly.security.mechanism.digest.PasswordDigestObtainer;
import org.wildfly.security.password.interfaces.DigestPassword;
import org.wildfly.security.util.ByteIterator;

/**
 * Implementation of the HTTP DIGEST authentication mechanism.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
final class DigestAuthenticationMechanism implements HttpServerAuthenticationMechanism {

    private static final String CHALLENGE_PREFIX = "Digest ";
    private static final String OPAQUE_VALUE = "00000000000000000000000000000000";
    private static final byte COLON = ':';

    private final Supplier<Provider[]> providers;
    private final CallbackHandler callbackHandler;
    private final NonceManager nonceManager;
    private final String configuredRealm;
    private final String domain;

    /**
     *
     * @param callbackHandler
     * @param nonceManager
     * @param configuredRealm
     */
    DigestAuthenticationMechanism(CallbackHandler callbackHandler, NonceManager nonceManager, String configuredRealm, String domain, Supplier<Provider[]> providers) {
        this.callbackHandler = callbackHandler;
        this.nonceManager = nonceManager;
        this.configuredRealm = configuredRealm;
        this.domain = domain;
        this.providers = providers;
    }

    @Override
    public String getMechanismName() {
        return DIGEST_NAME;
    }

    @Override
    public void evaluateRequest(final HttpServerRequest request) throws HttpAuthenticationException {
        List<String> authorizationValues = request.getRequestHeaderValues(AUTHORIZATION);

        if (authorizationValues != null) {
            for (String current : authorizationValues) {
                if (current.startsWith(CHALLENGE_PREFIX)) {
                    byte[] rawHeader = current.substring(CHALLENGE_PREFIX.length()).getBytes(UTF_8);
                    try {
                        HashMap<String, byte[]> responseTokens = parseResponse(rawHeader, UTF_8, false, httpDigest);
                        validateResponse(responseTokens, request);
                        return;
                    } catch (AuthenticationMechanismException e) {
                        httpDigest.trace("Failed to parse or validate the response", e);
                        request.badRequest(e.toHttpAuthenticationException(), response -> prepareResponse(selectRealm(), response, false));
                        return;
                    }
                }
            }
        }

        request.noAuthenticationInProgress(response -> prepareResponse(selectRealm(), response, false));
    }

    private void validateResponse(HashMap<String, byte[]> responseTokens, final HttpServerRequest request) throws AuthenticationMechanismException, HttpAuthenticationException {
        String nonce = convertToken(NONCE, responseTokens.get(NONCE));
        String messageRealm = convertToken(REALM, responseTokens.get(REALM));
        int nonceCount;
        if (!responseTokens.containsKey(NC)) {
            nonceCount = -1;
        } else {
            String nonceCountHex = convertToken(REALM, responseTokens.get(NC));
            nonceCount = Integer.parseInt(nonceCountHex, 16);
            if (nonceCount < 0) {
                throw httpDigest.invalidNonceCount(nonceCount);
            }
        }
        /*
         * We want to get the nonce checked ASAP so it is recorded as used in case some intermittent failure prevents validation.
         *
         * We act on the validity at the end where we can let the client know if it is stale.
         */
        byte[] salt = messageRealm.getBytes(UTF_8);
        boolean nonceValid = nonceManager.useNonce(nonce, salt, nonceCount);

        String username = convertToken(USERNAME, responseTokens.get(USERNAME));
        byte[] digestUri;
        if (responseTokens.containsKey(URI)) {
            digestUri = responseTokens.get(URI);
        } else {
            throw httpDigest.mechMissingDirective(URI);
        }
        byte[] response;
        if (responseTokens.containsKey(RESPONSE)) {
            response = ByteIterator.ofBytes(responseTokens.get(RESPONSE)).hexDecode().drain();
        } else {
            throw httpDigest.mechMissingDirective(RESPONSE);
        }
        String algorithm = convertToken(ALGORITHM, responseTokens.get(ALGORITHM));
        if (MD5.equals(algorithm) == false) {
            throw httpDigest.mechUnsupportedAlgorithm(algorithm);
        }

        MessageDigest messageDigest;
        try {
            messageDigest = MessageDigest.getInstance(MD5);
        } catch (NoSuchAlgorithmException e) {
            throw httpDigest.mechMacAlgorithmNotSupported(e);
        }

        if (!checkRealm(messageRealm)) {
            throw httpDigest.mechDisallowedClientRealm(messageRealm);
        }

        String selectedRealm = selectRealm();

        if (username.length() == 0) {
            fail();
            request.authenticationFailed(httpDigest.authenticationFailed(), httpResponse -> prepareResponse(selectedRealm, httpResponse, false));
            return;
        }

        byte[] hA1 = getH_A1(messageDigest, username, messageRealm);

        if (hA1 == null) {
            fail();
            request.authenticationFailed(httpDigest.authenticationFailed(), httpResponse -> prepareResponse(selectedRealm, httpResponse, false));
            return;
        }

        byte[] calculatedResponse = calculateResponseDigest(messageDigest, hA1, nonce, request.getRequestMethod(), digestUri, responseTokens.get(QOP), responseTokens.get(CNONCE), responseTokens.get(NC));

        if (Arrays.equals(response, calculatedResponse) == false) {
            fail();
            request.authenticationFailed(httpDigest.mechResponseTokenMismatch(), httpResponse -> prepareResponse(selectedRealm, httpResponse, false));
            return;
        }

        if (nonceValid == false) {
            request.authenticationInProgress(httpResponse -> prepareResponse(selectedRealm, httpResponse, true));
            return;
        }

        if (authorize(username)) {
            succeed();
            if (nonceCount < 0) {
                request.authenticationComplete(new HttpServerMechanismsResponder() {
                    @Override
                    public void sendResponse(HttpServerResponse response) throws HttpAuthenticationException {
                        sendAuthenticationInfoHeader(response, salt);
                    }
                });
            } else {
                // If we had a nonce count using it would extend the life of the nonce so we don't need to issue a new one.
                request.authenticationComplete();
            }
        } else {
            fail();
            request.authenticationFailed(httpDigest.authorizationFailed(username), httpResponse -> httpResponse.setStatusCode(HttpConstants.FORBIDDEN));
        }
    }

    private void sendAuthenticationInfoHeader(final HttpServerResponse response, byte[] salt) {
        String nextNonce = nonceManager.generateNonce(salt);
        response.addResponseHeader(HttpConstants.AUTHENTICATION_INFO, HttpConstants.NEXT_NONCE + "=\"" + nextNonce + "\"");
    }

    /**
     * Check if realm is offered by the server
     */
    private boolean checkRealm(String realm) throws AuthenticationMechanismException {
        String[] realms = getAvailableRealms();
        if (realms != null) {
            for (String current : realms) {
                if (realm.equals(current)) {
                    return true;
                }
            }
        }
        return false;
    }

    private byte[] calculateResponseDigest(MessageDigest messageDigest, byte[] hA1, String nonce, String method, byte[] digestUri, byte[] qop, byte[] cnonce, byte[] nc) {
        messageDigest.update(method.getBytes(UTF_8));
        messageDigest.update(COLON);
        byte[] hA2 = messageDigest.digest(digestUri);

        messageDigest.update(ByteIterator.ofBytes(hA1).hexEncode().drainToString().getBytes(UTF_8));
        messageDigest.update(COLON);
        messageDigest.update(nonce.getBytes(UTF_8));
        if(qop != null) {
            messageDigest.update(COLON);
            messageDigest.update(nc);
            messageDigest.update(COLON);
            messageDigest.update(cnonce);
            messageDigest.update(COLON);
            messageDigest.update(qop);
        }
        messageDigest.update(COLON);

        return messageDigest.digest(ByteIterator.ofBytes(hA2).hexEncode().drainToString().getBytes(UTF_8));
    }

    private byte[] getH_A1(final MessageDigest messageDigest, final String username, final String messageRealm) throws AuthenticationMechanismException {
        PasswordDigestObtainer obtainer = new PasswordDigestObtainer(callbackHandler, username, messageRealm, httpDigest, DigestPassword.ALGORITHM_DIGEST_MD5, messageDigest, providers, null, true, false);
        return obtainer.handleUserRealmPasswordCallbacks();
    }

    private String convertToken(final String name, final byte[] value) throws AuthenticationMechanismException {
        if (value == null) {
            throw httpDigest.mechMissingDirective(name);
        }

        return new String(value, UTF_8);
    }

    /**
     * Select the realm which should be sent to the client in the challenge.
     *
     * If a realm has been configured it takes priority.
     * Next the first available mechanism realm is selected.
     * If no mechanism is available or mechanism configured realm is not offered by the server, {@link IllegalStateException} is thrown.
     * @throws HttpAuthenticationException
     *
     */
    private String selectRealm() throws HttpAuthenticationException {
        try {
            if (configuredRealm != null) {
                if (!checkRealm(configuredRealm)) {
                    throw httpDigest.digestMechanismInvalidRealm(configuredRealm);
                }
                return configuredRealm;
            }
            String[] realms = getAvailableRealms();
            if (realms != null && realms.length > 0) {
                return realms[0];
            }
            throw httpDigest.digestMechanismRequireRealm();
        } catch (AuthenticationMechanismException e) {
            throw e.toHttpAuthenticationException();
        }
    }

    private String[] getAvailableRealms() throws AuthenticationMechanismException {
        final AvailableRealmsCallback availableRealmsCallback = new AvailableRealmsCallback();
        try {
            callbackHandler.handle(new Callback[] { availableRealmsCallback });
            return availableRealmsCallback.getRealmNames();
        } catch (UnsupportedCallbackException ignored) {
            return new String[0];
        } catch (AuthenticationMechanismException e) {
            throw e;
        } catch (IOException e) {
            throw httpDigest.mechCallbackHandlerFailedForUnknownReason(e);
        }
    }

    private void prepareResponse(String realmName, HttpServerResponse response, boolean stale) throws HttpAuthenticationException {
        StringBuilder sb = new StringBuilder(CHALLENGE_PREFIX);
        sb.append(REALM).append("=\"").append(DigestQuote.quote(realmName)).append("\"");

        if (domain != null) {
            sb.append(", ").append(DOMAIN).append("=\"").append(domain).append("\"");
        }
        sb.append(", ").append(NONCE).append("=\"").append(nonceManager.generateNonce(realmName.getBytes(StandardCharsets.UTF_8))).append("\"");
        sb.append(", ").append(OPAQUE).append("=\"").append(OPAQUE_VALUE).append("\"");
        if (stale) {
            sb.append(", ").append(STALE).append("=true");
        }
        sb.append(", ").append(ALGORITHM).append("=").append(MD5);
        sb.append(", ").append(QOP).append("=").append(AUTH);

        response.addResponseHeader(WWW_AUTHENTICATE, sb.toString());
        response.setStatusCode(UNAUTHORIZED);
    }

    private boolean authorize(String username) throws AuthenticationMechanismException {
        AuthorizeCallback authorizeCallback = new AuthorizeCallback(username, username);

        try {
            callbackHandler.handle(new Callback[] {authorizeCallback});

            return authorizeCallback.isAuthorized();
        } catch (UnsupportedCallbackException e) {
            return false;
        } catch (Throwable t) {
            throw httpDigest.mechCallbackHandlerFailedForUnknownReason(t);
        }
    }

    private void succeed() throws AuthenticationMechanismException {
        try {
            callbackHandler.handle(new Callback[] { AuthenticationCompleteCallback.SUCCEEDED });
        } catch (Throwable t) {
            throw httpDigest.mechCallbackHandlerFailedForUnknownReason(t);
        }
    }

    private void fail() throws AuthenticationMechanismException {
        try {
            callbackHandler.handle(new Callback[] { AuthenticationCompleteCallback.FAILED });
        } catch (Throwable t) {
            throw httpDigest.mechCallbackHandlerFailedForUnknownReason(t);
        }
    }
}