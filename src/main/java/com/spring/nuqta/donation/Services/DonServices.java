package com.spring.nuqta.donation.Services;

import com.spring.nuqta.base.Services.BaseServices;
import com.spring.nuqta.donation.Dto.AcceptDonationRequestDto;
import com.spring.nuqta.donation.Entity.DonEntity;
import com.spring.nuqta.donation.Repo.DonRepo;
import com.spring.nuqta.enums.DonStatus;
import com.spring.nuqta.enums.Status;
import com.spring.nuqta.exception.GlobalException;
import com.spring.nuqta.notifications.Dto.NotificationRequest;
import com.spring.nuqta.notifications.Services.NotificationService;
import com.spring.nuqta.organization.Entity.OrgEntity;
import com.spring.nuqta.request.Entity.ReqEntity;
import com.spring.nuqta.request.Repo.ReqRepo;
import com.spring.nuqta.usermanagement.Entity.UserEntity;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DonServices extends BaseServices<DonEntity, Long> {

    private final DonRepo donRepository;
    private final ReqRepo reqRepository;
    private final SendEmail sendMailToDoner;
    private final NotificationService notificationService;
    private final MessageSource ms;


    @Cacheable(value = "donation")
    public List<DonEntity> findTopConservatism(String conservatism) {
        List<DonEntity> entities = donRepository.findFirstByConservatismContainingIgnoreCase(conservatism);
        if (entities.isEmpty()) {
            throw new GlobalException("error.donation.notFound", HttpStatus.NOT_FOUND);
        }
        return entities;
    }

    @Cacheable(value = "donation")
    public List<DonEntity> findTopCity(String city) {
        List<DonEntity> entities = donRepository.findFirstByCityContainingIgnoreCase(city);
        if (entities.isEmpty()) {
            throw new GlobalException("error.donation.notFound", HttpStatus.NOT_FOUND);
        }
        return entities;
    }

    @Override
    @Cacheable(value = "donation", key = "#id")
    public DonEntity findById(Long id) {
        if (id == null || id <= 0) {
            String msg = messageParam(id, "error.invalid.id");
            throw new GlobalException(msg, HttpStatus.BAD_REQUEST);
        }

        Optional<DonEntity> entity = donRepository.findById(id);
        if (entity.isEmpty()) {
            String msg = messageParam(id, "error.donation.notFoundById");
            throw new GlobalException(msg, HttpStatus.NOT_FOUND);
        }

        return entity.get();
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "donation", allEntries = true),
            @CacheEvict(value = "users", allEntries = true),
            @CacheEvict(value = "requests", allEntries = true)
    })
    public void acceptDonationRequest(AcceptDonationRequestDto dto) throws MessagingException {
        // Fetch entities with optimistic locking
        DonEntity donation = donRepository.findById(dto.getDonationId())
                .orElseThrow(() -> new GlobalException("error.don.notFound", HttpStatus.NOT_FOUND));

        ReqEntity request = reqRepository.findById(dto.getRequestId())
                .orElseThrow(() -> new GlobalException("error.request.notFound", HttpStatus.NOT_FOUND));

        // Validate donation can accept requests
        if (donation.getStatus() != DonStatus.VALID) {
            throw new GlobalException("error.donation.notValidForAccept", HttpStatus.CONFLICT);
        }

        if (donation.getConfirmDonate()) {
            throw new GlobalException("error.donation.alreadyConfirmed", HttpStatus.CONFLICT);
        }

        // Check if already accepted
        if (donation.getAcceptedRequests().contains(request)) {
            throw new GlobalException("error.request.alreadyAccepted", HttpStatus.CONFLICT);
        }

        // Update relationship using helper method
        donation.addAcceptedRequest(request);
        request.addDonation(donation);

        // Update donation status and dates
        LocalDateTime currentDate = LocalDateTime.now();
        donation.setDonationDate(currentDate);
        donation.setStartDonation(currentDate);
        donation.setAmount(donation.getAmount() + 1);
        donation.setConfirmDonateReqId(dto.getRequestId());

        // Save entities
        donRepository.save(donation);
        reqRepository.save(request);

        // Send notifications
//        sendNotificationIfApplicable(donation, request);

        // Send email if applicable
        sendMailToDoner.sendMail(donation, request);

    }

    @Transactional
    @Caching(
            evict = {
                    @CacheEvict(value = "donation", allEntries = true),
                    @CacheEvict(value = "users", allEntries = true),
                    @CacheEvict(value = "requests", allEntries = true)
            })
    public void deleteAcceptedDonationRequest(AcceptDonationRequestDto dto) throws MessagingException {
        if (dto.getDonationId() == null || dto.getRequestId() == null) {
            throw new GlobalException("error.invalid.input", HttpStatus.BAD_REQUEST);
        }

        DonEntity donation = donRepository.findById(dto.getDonationId())
                .orElseThrow(() -> new GlobalException("error.don.notFound", HttpStatus.NOT_FOUND));

        ReqEntity request = reqRepository.findById(dto.getRequestId())
                .orElseThrow(() -> new GlobalException("error.request.notFound", HttpStatus.NOT_FOUND));

        log.info("Deleting accepted donation request for donation id: {}", donation.getAcceptedRequests());

        if (!donation.getAcceptedRequests().contains(request) ||
                !request.getDonations().contains(donation)) {
            throw new GlobalException("error.request.notLinked", HttpStatus.CONFLICT);
        }

        if (donation.getConfirmDonate()) {
            throw new GlobalException("error.donation.confirmed", HttpStatus.FORBIDDEN);
        }

        donation.getAcceptedRequests().remove(request);
        request.getDonations().remove(donation);
        donation.setStatus(DonStatus.VALID);
        donation.setDonationDate(null);
        donation.setStartDonation(null);
        donation.setAmount(donation.getAmount() - 1);
        donation.setConfirmDonateReqId(0L);

        donRepository.save(donation);
        reqRepository.save(request);

        sendMailToDoner.sendMailRejected(donation, request);
    }

    void sendNotificationIfApplicable(DonEntity donation, ReqEntity request) {
        if (donation == null || request == null) {
            log.warn("Donation or request is null. Notification not sent.");
            return;
        }

        UserEntity donor = donation.getUser();
        if (donor == null || donor.getFcmToken() == null) {
            log.warn("Donor or FCM token is null. Notification not sent.");
            return;
        }

        String donorName = donor.getUsername();
        String[] msParam = {donorName};
        String message = ms.getMessage("notification.requestAcceptedMessage", msParam, LocaleContextHolder.getLocale());

        // Send notification to the request user (if user exists and has an FCM token)
        Optional.ofNullable(request.getUser())
                .map(UserEntity::getFcmToken)
                .ifPresent(token -> {
                    notificationService.sendNotification(new NotificationRequest(token, "notification.requestAcceptedTitle", message));
                });

        // Send notification to the request organization (if organization exists and has an FCM token)
        Optional.ofNullable(request.getOrganization())
                .map(OrgEntity::getFcmToken)
                .ifPresent(token -> {
                    notificationService.sendNotification(new NotificationRequest(token, "notification.requestAcceptedTitle", message));
                });
    }

    @Transactional
    @Caching(
            evict = {
                    @CacheEvict(value = "donation", allEntries = true),
                    @CacheEvict(value = "users", allEntries = true),
                    @CacheEvict(value = "requests", allEntries = true)
            })
    public void markAsAccepted(Long donationId, Long requestId) {
        DonEntity donation = donRepository.findById(donationId)
                .orElseThrow(() -> new GlobalException("error.donation.notFound", HttpStatus.NOT_FOUND));

        ReqEntity request = reqRepository.findById(requestId)
                .orElseThrow(() -> new GlobalException("error.request.notFound", HttpStatus.NOT_FOUND));

        donation.setConfirmDonate(true);
        donation.setStatus(DonStatus.INVALID);
        donation.setStartDonation(LocalDateTime.now().plusMonths(3));

        if (request.getAmount() != null && request.getDonations() != null) {
            if (request.getDonations().size() == request.getAmount()) {
                request.setStatus(Status.FULFILLED);
            } else {
                request.setStatus(Status.OPEN);
            }
        }

        donRepository.save(donation);

    }

    @Caching(
            evict = {
                    @CacheEvict(value = "users", allEntries = true),
                    @CacheEvict(value = "donation", allEntries = true),
                    @CacheEvict(value = "requests", allEntries = true)
            })
    @Scheduled(cron = "0 0 0 * * ?") // Run at midnight every day
    //    @Scheduled(cron = "* * * * * ?") // Run every minute instead of every second
    public void updateDonationStatuses() {
        List<DonEntity> donations = donRepository.findByStatusAndConfirmDonate(DonStatus.INVALID, true);

        if (!donations.isEmpty()) {
            log.info("Found {} donations with INVALID status to process", donations.size());
        } else {
            log.debug("No donations with INVALID status found");
            return;
        }

        List<DonEntity> expiredDonations = new ArrayList<>();

        for (DonEntity donation : donations) {
            try {
                if (donation.isExpired()) {
                    // Update the donation status and related fields
//                    donation.setStatus(DonStatus.VALID); // Or should this be EXPIRED?
                    donation.setConfirmDonate(false);
                    donation.setConfirmDonateReqId(0L);
                    expiredDonations.add(donation);

                    log.debug("Marking donation {} as expired for user: {}",
                            donation.getId(),
                            donation.getUser() != null ? donation.getUser().getUsername() : "Unknown");
                }
            } catch (Exception e) {
                log.error("Error processing donation with ID: {}", donation.getId(), e);
            }
        }

        if (!expiredDonations.isEmpty()) {
            try {
                donRepository.saveAll(expiredDonations);
                log.info("Successfully updated {} expired donations", expiredDonations.size());
            } catch (Exception e) {
                log.error("Error saving expired donations", e);
            }
        }
    }

    public String messageParam(Long id, String message) {
        String[] msParam = {id != null ? id.toString() : "null"};
        return ms.getMessage(message, msParam, LocaleContextHolder.getLocale());
    }
}
