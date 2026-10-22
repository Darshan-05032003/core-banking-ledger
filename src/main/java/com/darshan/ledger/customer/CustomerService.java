package com.darshan.ledger.customer;

import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.error.ConflictException;
import com.darshan.ledger.common.error.ResourceNotFoundException;
import com.darshan.ledger.common.pii.PiiMasker;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);
    private static final int MINIMUM_AGE_YEARS = 18;

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;
    private final Clock clock;

    public CustomerService(CustomerRepository customerRepository, CustomerMapper customerMapper, Clock clock) {
        this.customerRepository = customerRepository;
        this.customerMapper = customerMapper;
        this.clock = clock;
    }

    public CustomerResponse onboard(OnboardCustomerRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        requireAdult(request.dateOfBirth());
        if (customerRepository.existsByEmail(email)) {
            throw new ConflictException(CustomerErrorCode.EMAIL_ALREADY_REGISTERED,
                    "A customer with this email is already registered");
        }
        Customer customer = customerRepository.save(new Customer(
                request.fullName().trim(), email, request.phone(), request.dateOfBirth(), clock.instant()));
        log.info("Customer {} onboarded, email {}", customer.getId(), PiiMasker.maskEmail(email));
        return customerMapper.toResponse(customer);
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(Long customerId) {
        return customerMapper.toResponse(requireCustomer(customerId));
    }

    public CustomerResponse verifyKyc(Long customerId) {
        Customer customer = requireCustomer(customerId);
        customer.verifyKyc(clock.instant());
        log.info("Customer {} KYC verified", customerId);
        return customerMapper.toResponse(customer);
    }

    public CustomerResponse rejectKyc(Long customerId, RejectKycRequest request) {
        Customer customer = requireCustomer(customerId);
        customer.rejectKyc(request.reason().trim(), clock.instant());
        log.info("Customer {} KYC rejected", customerId);
        return customerMapper.toResponse(customer);
    }

    @Transactional(readOnly = true)
    public Customer requireCustomer(Long customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId));
    }

    private void requireAdult(LocalDate dateOfBirth) {
        LocalDate today = LocalDate.now(clock);
        if (dateOfBirth.plusYears(MINIMUM_AGE_YEARS).isAfter(today)) {
            throw new BusinessRuleException(CustomerErrorCode.CUSTOMER_UNDERAGE,
                    "Customer must be at least " + MINIMUM_AGE_YEARS + " years old");
        }
    }
}
