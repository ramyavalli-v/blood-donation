package com.spring.nuqta.donation.Services;

import com.spring.nuqta.donation.Dto.AcceptDonationRequestDto;
import com.spring.nuqta.donation.Entity.DonEntity;
import com.spring.nuqta.donation.Repo.DonRepo;
import com.spring.nuqta.enums.DonStatus;
import com.spring.nuqta.exception.GlobalException;
import com.spring.nuqta.notifications.Services.NotificationService;
import com.spring.nuqta.organization.Entity.OrgEntity;
import com.spring.nuqta.request.Entity.ReqEntity;
import com.spring.nuqta.request.Repo.ReqRepo;
import com.spring.nuqta.usermanagement.Entity.UserEntity;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DonServicesTest {

    @Mock
    private DonRepo donRepository;

    @Mock
    private ReqRepo reqRepository;

    @Mock
    private SendEmail sendMailToDoner;

    @Mock
    private NotificationService notificationService;

    @Mock
    private MessageSource messageSource;

    @Mock
    private CacheManager cacheManager;

    @InjectMocks
    private DonServices donServices;

    private DonEntity validDonation;
    private DonEntity invalidDonation;
    private ReqEntity request;
    private UserEntity donor;
    private OrgEntity organization;
    private AcceptDonationRequestDto dto;

    @BeforeEach
    void setUp() {
        donor = new UserEntity();
        donor.setId(1L);
        donor.setUsername("Test Donor");
        donor.setFcmToken("donor-fcm-token");

        organization = new OrgEntity();
        organization.setId(1L);
        organization.setFcmToken("org-fcm-token");

        validDonation = new DonEntity();
        validDonation.setId(1L);
        validDonation.setStatus(DonStatus.VALID);
        validDonation.setUser(donor);
        validDonation.setConfirmDonate(false);

        invalidDonation = new DonEntity();
        invalidDonation.setId(2L);
        invalidDonation.setStatus(DonStatus.INVALID);
        invalidDonation.setUser(donor);
        invalidDonation.setConfirmDonate(true);

        request = new ReqEntity();
        request.setId(1L);
        request.setUser(donor);
        request.setOrganization(organization);

        dto = new AcceptDonationRequestDto();
        dto.setDonationId(1L);
        dto.setRequestId(1L);
    }

    @Test
    void findById_WithValidId_ReturnsDonation() {
        when(donRepository.findById(1L)).thenReturn(Optional.of(validDonation));

        DonEntity result = donServices.findById(1L);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals(DonStatus.VALID, result.getStatus());
    }

    @Test
    void findById_WithInvalidId_ThrowsException() {
        when(messageSource.getMessage(eq("error.invalid.id"), any(), any()))
                .thenReturn("Invalid ID");

        GlobalException exception = assertThrows(GlobalException.class, () -> {
            donServices.findById(0L);
        });

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("Invalid ID", exception.getMessage());
    }

    @Test
    void findById_WithNonExistentId_ThrowsException() {
        when(donRepository.findById(anyLong())).thenReturn(Optional.empty());
        when(messageSource.getMessage(any(), any(), any())).thenReturn("Not found");

        GlobalException exception = assertThrows(GlobalException.class, () -> {
            donServices.findById(999L);
        });

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    void findTopConservatism_WithValidConservatism_ReturnsDonations() {
        List<DonEntity> donations = new ArrayList<>();
        donations.add(validDonation);
        when(donRepository.findFirstByConservatismContainingIgnoreCase(any())).thenReturn(donations);

        List<DonEntity> result = donServices.findTopConservatism("test");

        assertFalse(result.isEmpty());
        assertEquals(1, result.size());
    }

    @Test
    void findTopConservatism_WithNoResults_ThrowsException() {
        when(donRepository.findFirstByConservatismContainingIgnoreCase(any())).thenReturn(new ArrayList<>());

        GlobalException exception = assertThrows(GlobalException.class, () -> {
            donServices.findTopConservatism("nonexistent");
        });

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

//    @Test
//    void acceptDonationRequest_WithValidData_UpdatesEntities() throws MessagingException {
//        when(donRepository.findById(1L)).thenReturn(Optional.of(validDonation));
//        when(reqRepository.findById(1L)).thenReturn(Optional.of(request));
//
//        donServices.acceptDonationRequest(dto);
//
//        assertEquals(DonStatus.VALID, validDonation.getStatus());
//        assertTrue(validDonation.getAcceptedRequests().contains(request));
//        assertTrue(request.getDonations().contains(validDonation));
//        assertNotNull(validDonation.getDonationDate());
//        assertNotNull(validDonation.getStartDonation());
//
//        verify(donRepository, times(1)).save(validDonation);
//        verify(reqRepository, times(1)).save(request);
//        verify(sendMailToDoner, times(1)).sendMail(validDonation, request);
//    }

    @Test
    void acceptDonationRequest_WithAlreadyAccepted_ThrowsException() throws MessagingException {
        validDonation.addAcceptedRequest(request);
        request.addDonation(validDonation);

        when(donRepository.findById(1L)).thenReturn(Optional.of(validDonation));
        when(reqRepository.findById(1L)).thenReturn(Optional.of(request));

        GlobalException exception = assertThrows(GlobalException.class, () -> {
            donServices.acceptDonationRequest(dto);
        });

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("error.request.alreadyAccepted", exception.getMessage());
    }

//    @Test
//    void deleteAcceptedDonationRequest_WithValidData_UpdatesEntities() throws MessagingException {
//        validDonation.addAcceptedRequest(request);
//        request.addDonation(validDonation);
//        validDonation.setStatus(DonStatus.INVALID);
//
//        when(donRepository.findById(1L)).thenReturn(Optional.of(validDonation));
//        when(reqRepository.findById(1L)).thenReturn(Optional.of(request));
//
//        donServices.deleteAcceptedDonationRequest(dto);
//
//        assertEquals(DonStatus.VALID, validDonation.getStatus());
//        assertFalse(validDonation.getAcceptedRequests().contains(request));
//        assertFalse(request.getDonations().contains(validDonation));
//        assertNull(validDonation.getDonationDate());
//        assertNull(validDonation.getStartDonation());
//
//        verify(donRepository, times(1)).save(validDonation);
//        verify(reqRepository, times(1)).save(request);
//        verify(sendMailToDoner, times(1)).sendMailRejected(validDonation, request);
//    }

    @Test
    void deleteAcceptedDonationRequest_WithConfirmedDonation_ThrowsException() {
        validDonation.setConfirmDonate(true);
        validDonation.addAcceptedRequest(request);
        request.addDonation(validDonation);

        when(donRepository.findById(1L)).thenReturn(Optional.of(validDonation));
        when(reqRepository.findById(1L)).thenReturn(Optional.of(request));

        GlobalException exception = assertThrows(GlobalException.class, () -> {
            donServices.deleteAcceptedDonationRequest(dto);
        });

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        assertEquals("error.donation.confirmed", exception.getMessage());
    }

}