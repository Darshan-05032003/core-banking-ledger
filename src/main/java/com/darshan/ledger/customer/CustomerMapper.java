package com.darshan.ledger.customer;

import com.darshan.ledger.common.pii.PiiMasker;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper
public interface CustomerMapper {

    @Mapping(target = "maskedEmail", source = "email", qualifiedByName = "maskEmail")
    @Mapping(target = "maskedPhone", source = "phone", qualifiedByName = "maskPhone")
    CustomerResponse toResponse(Customer customer);

    @Named("maskEmail")
    default String maskEmail(String email) {
        return PiiMasker.maskEmail(email);
    }

    @Named("maskPhone")
    default String maskPhone(String phone) {
        return PiiMasker.maskPhone(phone);
    }
}
