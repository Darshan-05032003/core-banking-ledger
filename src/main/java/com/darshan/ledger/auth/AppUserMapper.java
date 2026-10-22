package com.darshan.ledger.auth;

import org.mapstruct.Mapper;

@Mapper
public interface AppUserMapper {

    AppUserResponse toResponse(AppUser user);
}
