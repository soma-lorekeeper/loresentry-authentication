package com.loresentry.authentication.adapter.in.web.mapper;

import com.loresentry.authentication.adapter.in.web.dto.AuthResponses;
import com.loresentry.authentication.application.port.in.AccountUseCase;
import com.loresentry.authentication.application.port.in.AuthFailure.Consumption;
import com.loresentry.authentication.application.port.in.LoginUseCase;
import com.loresentry.authentication.application.port.in.TokenPair;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AuthResponseMapper {
    AuthResponses.PreparedLogin preparedLogin(LoginUseCase.PreparedLogin login);

    AuthResponses.Tokens tokens(TokenPair tokens);

    AuthResponses.Profile profile(AccountUseCase.Profile profile);

    @Mapping(target = ".", source = "tokens")
    @Mapping(
            target = "loginRequestConsumed",
            source = "consumption",
            qualifiedByName = "consumptionFlag")
    AuthResponses.Callback callback(LoginUseCase.LoginResult login);

    @Named("consumptionFlag")
    default Boolean consumptionFlag(Consumption consumption) {
        return switch (consumption) {
            case CONSUMED -> true;
            case NOT_CONSUMED -> false;
            case UNKNOWN -> null;
        };
    }
}
