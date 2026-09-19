package com.loresentry.authentication.adapter.in.web.mapper;

import com.loresentry.authentication.adapter.in.web.dto.AuthRequests;
import com.loresentry.authentication.application.port.in.LoginUseCase;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AuthRequestMapper {
    LoginUseCase.Callback callback(AuthRequests.Callback request);
}
