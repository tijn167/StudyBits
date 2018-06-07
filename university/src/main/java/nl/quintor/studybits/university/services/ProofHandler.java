package nl.quintor.studybits.university.services;

import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import nl.quintor.studybits.indy.wrapper.dto.*;
import nl.quintor.studybits.university.dto.*;
import nl.quintor.studybits.university.dto.Proof;
import nl.quintor.studybits.university.dto.ProofAttribute;
import nl.quintor.studybits.university.entities.ClaimSchema;
import nl.quintor.studybits.university.entities.ProofRecord;
import nl.quintor.studybits.university.entities.University;
import nl.quintor.studybits.university.entities.User;
import nl.quintor.studybits.university.helpers.Lazy;
import nl.quintor.studybits.university.models.SchemaDefinitionModel;
import nl.quintor.studybits.university.repositories.ClaimSchemaRepository;
import nl.quintor.studybits.university.repositories.ProofRecordRepository;
import nl.quintor.studybits.university.repositories.UserRepository;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.dozer.Mapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@AllArgsConstructor(onConstructor = @__(@Autowired))
public abstract class ProofHandler<T extends Proof> {

    protected final UniversityService universityService;
    protected final ProofRecordRepository proofRecordRepository;
    protected final ClaimSchemaRepository claimSchemaRepository;
    protected final UserRepository userRepository;
    protected final Mapper mapper;
    protected final Lazy<List<ProofAttribute>> proofAttributes = new Lazy<>();

    protected abstract Class<T> getProofType();

    @Transactional
    protected abstract boolean handleProof(User prover, ProofRecord proofRecord, T proof);

    private ProofRequestInfoDto toDto(ProofRecord proofRecord, List<String> attributes) {
        ProofRequestInfoDto result = mapper.map(proofRecord, ProofRequestInfoDto.class);
        result.setAttributes(attributes);
        return result;
    }

    @SneakyThrows
    private T newProof() {
        return getProofType().newInstance();
    }

    public Version getProofVersion() {
        return ClaimUtils.getVersion(getProofType());
    }

    public String getProofName() {
        return getProofVersion().getName();
    }

    private List<ProofAttribute> getProofAttributes() {
        return proofAttributes.getOrCompute(() -> ClaimUtils.getProofAttributes(getProofType()));
    }

    public List<ProofRequestInfoDto> findProofRequests(Long userId) {
        List<ProofRecord> proofRecords = proofRecordRepository.findAllByUserIdAndProofNameAndProofJsonIsNull(userId, getProofName());
        if (proofRecords.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> attributes = getProofAttributes()
                .stream()
                .map(x -> x.getAttributeName())
                .collect(Collectors.toList());
        return proofRecords
                .stream()
                .map(proofRecord -> toDto(proofRecord, attributes))
                .collect(Collectors.toList());
    }

    @Transactional
    public ProofRecord addProofRequest(Long userId) {
        User user = userRepository.getOne(userId);
        Version version = ClaimUtils.getVersion(getProofType());
        String nonce = RandomStringUtils.randomNumeric(28, 36);
        ProofRecord proofRecord = new ProofRecord(null, user, version.getName(), version.getVersion(), nonce, null, null);
        return proofRecordRepository.save(proofRecord);
    }

    public AuthcryptedMessage getProofRequestMessage(Long userId, Long proofRecordId) {
        ProofRecord proofRecord = getProofRecord(proofRecordId);
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException(String.format("Could not find user with id: {}", userId)));
        return getProofRequestMessage(user, proofRecord);
    }

    public AuthcryptedMessage getProofRequestMessage(User user, ProofRecord proofRecord) {
        University university = Objects.requireNonNull(user.getUniversity());
        ProofRequest proofRequest = getProofRequest(university, user, proofRecord);
        return universityService.authEncrypt(university.getName(), proofRequest);
    }

    @Transactional
    public Boolean handleProof(Long proverId, Long proofRecordId, AuthcryptedMessage authcryptedMessage) {
        User prover = userRepository.findById(proverId).orElseThrow(() -> new IllegalArgumentException(String.format("User with id: %d not known", proverId)));
        Validate.notNull(prover.getConnection(), "User onboarding incomplete!");

        ProofRecord proofRecord = getProofRecord(proofRecordId);
        Validate.validState(StringUtils.isEmpty(proofRecord.getProofJson()), String.format("UserId %s already provided proof for proofRecordId %s.", proverId, proofRecordId));
        University university = Objects.requireNonNull(prover.getUniversity());
        ProofRequest proofRequest = getProofRequest(university, prover, proofRecord);
        T verifiedProof = getVerifiedProof(university.getName(), proofRequest, authcryptedMessage);
        proofRecord.setProofJson(ServiceUtils.objectToJson(verifiedProof));
        Boolean handled = handleProof(prover, proofRecord, verifiedProof);
        if (handled) {
            proofRecordRepository.save(proofRecord);
        }
        return handled;
    }

    public T getProof(Long userId, Long proofRecordId) {
        ProofRecord proofRecord = getProofRecord(proofRecordId);
        Validate.validState(StringUtils.isEmpty(proofRecord.getProofJson()), String.format("UserId %s did not provide proof for proofRecordId %s.", userId, proofRecord));
        User user = Objects.requireNonNull(proofRecord.getUser());
        return ServiceUtils.jsonToObject(proofRecord.getProofJson(), getProofType());
    }

    private T getVerifiedProof(String universityName, ProofRequest proofRequest, AuthcryptedMessage authcryptedMessage) {
        nl.quintor.studybits.indy.wrapper.dto.Proof proof = universityService
                .authDecrypt(universityName, authcryptedMessage, nl.quintor.studybits.indy.wrapper.dto.Proof.class);
        List<nl.quintor.studybits.indy.wrapper.dto.ProofAttribute> attributes = universityService
                .getVerifiedProofAttributes(universityName, proofRequest, proof);
        T result = newProof();
        attributes.forEach(attribute -> writeAttributeField(result, attribute));
        return result;
    }

    private void writeAttributeField(T result, nl.quintor.studybits.indy.wrapper.dto.ProofAttribute proofAttribute) {
        try {
            FieldUtils.writeField(result, proofAttribute.getKey(), proofAttribute.getValue(), true);
        } catch (IllegalAccessException e) {
            String message = String.format("Unable to write proof attribute to field '%s' of type '%s'.", proofAttribute.getKey(), result.getClass().getName());
            throw new IllegalStateException(message, e);
        }
    }

    private ProofRequest getProofRequest(University university, User user, ProofRecord proofRecord) {
        String theirDid = Objects.requireNonNull(user.getConnection()).getDid();
        return ProofRequest
                .builder()
                .name(proofRecord.getProofName())
                .version(proofRecord.getProofVersion())
                .nonce(proofRecord.getNonce())
                .theirDid(theirDid)
                .requestedAttributes(getRequestedAttributes(university))
                .build();
    }

    private Map<String, AttributeInfo> getRequestedAttributes(University university) {
        Map<Version, Optional<ClaimSchema>> claimSchemaLookup = getProofAttributes()
                .stream()
                .flatMap(proofAttribute -> proofAttribute.getSchemaVersions().stream())
                .distinct()
                .collect(Collectors.toMap(v -> v, v -> findClaimSchema(university.getId(), v)));

        return getProofAttributes()
                .stream()
                .collect(Collectors.toMap(
                        ProofAttribute::getFieldName,
                        proofAttribute -> getAttributeInfo(proofAttribute, claimSchemaLookup, university.getName()))
                );
    }

    private AttributeInfo getAttributeInfo(ProofAttribute proofAttribute, Map<Version, Optional<ClaimSchema>> claimSchemaLookup, String universityName) {
        List<Version> versions = proofAttribute.getSchemaVersions();
        if (versions.isEmpty()) {
            return new AttributeInfo(proofAttribute.getAttributeName(), Optional.empty());
        }

        List<Filter> filters = versions
                .stream()
                .map(claimSchemaLookup::get)
                .flatMap(entry -> createFilter(entry, universityName))
                .collect(Collectors.toList());
        Validate.notEmpty(filters, "No claim issuers found for field '%s'.", proofAttribute.getField());
        return new AttributeInfo(proofAttribute.getAttributeName(), Optional.of(filters));
    }

    private Stream<Filter> createFilter(Optional<ClaimSchema> claimSchema, String universityName) {
        return claimSchema
                .map(claimSchema1 -> createFilter(claimSchema1, universityName))
                .orElseGet(Stream::empty);
    }

    private Stream<Filter> createFilter(ClaimSchema claimSchema, String universityName) {

        return claimSchema
                .getClaimIssuers()
                .stream()
                .map(claimIssuer ->  new Filter(Optional.empty(), Optional.of(claimSchema.getSchemaName()), Optional.of(claimSchema.getSchemaVersion())));
    }


    private Optional<ClaimSchema> findClaimSchema(Long universityId, Version schemaVersion) {
        return claimSchemaRepository.findByUniversityIdAndSchemaNameAndSchemaVersion(
                universityId,
                schemaVersion.getName(),
                schemaVersion.getVersion());
    }


    private ProofRecord getProofRecord(Long proofRecordId) {
        ProofRecord proofRecord = proofRecordRepository
                .findById(proofRecordId)
                .orElseThrow(() -> new IllegalArgumentException("Proof record not found."));
        Objects.requireNonNull(proofRecord.getUser(), "Proof record without user.");

        Version version = getProofVersion();
        Validate.isTrue(version.getName().equals(proofRecord.getProofName()), "Proof name mismatch.");
        Validate.isTrue(version.getVersion().equals(proofRecord.getProofVersion()), "Proof version mismatch.");
        return proofRecord;
    }

}
