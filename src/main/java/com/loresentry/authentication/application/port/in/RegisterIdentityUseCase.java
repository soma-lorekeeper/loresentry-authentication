package com.loresentry.authentication.application.port.in;

import com.loresentry.authentication.application.port.out.OidcClient.Identity;
import com.loresentry.authentication.domain.User;

/** Accepts only a provider identity already verified by the OIDC adapter. */
public interface RegisterIdentityUseCase { User register(Identity identity); }
