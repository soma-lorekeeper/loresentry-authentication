package com.loresentry.authentication.adapter.out.persistence;

import com.loresentry.authentication.application.port.out.AccountStore.Account;
import com.loresentry.authentication.domain.OAuthIdentity;
import com.loresentry.authentication.domain.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AccountEntityMapper {
    UserEntity toEntity(User user);

    User toDomain(UserEntity user);

    OAuthIdentityId identityId(OAuthIdentity identity);

    @Mapping(target = "id", source = "identity")
    @Mapping(target = "user", source = "user")
    @Mapping(target = "email", source = "identity.email")
    OAuthIdentityEntity toEntity(OAuthIdentity identity, UserEntity user);

    @Mapping(target = "provider", source = "id.provider")
    @Mapping(target = "providerId", source = "id.providerId")
    @Mapping(target = "userId", source = "user.id")
    OAuthIdentity toDomain(OAuthIdentityEntity identity);

    @Mapping(target = "identity", source = ".")
    Account toAccount(OAuthIdentityEntity identity);
}
