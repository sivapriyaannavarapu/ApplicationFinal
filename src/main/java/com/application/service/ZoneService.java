package com.application.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.application.dto.DistributionRequestDTO;
import com.application.dto.EmployeesDto;
import com.application.entity.AcademicYear;
import com.application.entity.AdminApp;
import com.application.entity.BalanceTrack;
import com.application.entity.City;
import com.application.entity.Distribution;
import com.application.entity.State;
import com.application.entity.ZonalAccountant;
import com.application.entity.Zone;
import com.application.repository.AcademicYearRepository;
import com.application.repository.AdminAppRepository;
import com.application.repository.AppIssuedTypeRepository;
import com.application.repository.BalanceTrackRepository;
import com.application.repository.CampusProViewRepository;
import com.application.repository.CityRepository;
import com.application.repository.DistributionRepository;
import com.application.repository.EmployeeRepository;
import com.application.repository.StateRepository;
import com.application.repository.ZonalAccountantRepository;
import com.application.repository.ZoneRepository;

import lombok.NonNull;

@Service
public class ZoneService {

    private final AcademicYearRepository academicYearRepository;
    private final StateRepository stateRepository;
    private final CityRepository cityRepository;
    private final ZoneRepository zoneRepository;
    private final AppIssuedTypeRepository appIssuedTypeRepository;
    private final EmployeeRepository employeeRepository;
    private final BalanceTrackRepository balanceTrackRepository;
    private final DistributionRepository distributionRepository;
    private final ZonalAccountantRepository zonalAccountantRepository;
    @Autowired
    private AdminAppRepository adminAppRepository;
    @Autowired
    private CampusProViewRepository campusProViewRepository;

    public ZoneService(AcademicYearRepository academicYearRepository, StateRepository stateRepository,
            CityRepository cityRepository, ZoneRepository zoneRepository,
            AppIssuedTypeRepository appIssuedTypeRepository, EmployeeRepository employeeRepository,
            BalanceTrackRepository balanceTrackRepository,
            DistributionRepository distributionRepository, ZonalAccountantRepository zonalAccountantRepository) {
        this.academicYearRepository = academicYearRepository;
        this.stateRepository = stateRepository;
        this.cityRepository = cityRepository;
        this.zoneRepository = zoneRepository;
        this.appIssuedTypeRepository = appIssuedTypeRepository;
        this.employeeRepository = employeeRepository;
        this.balanceTrackRepository = balanceTrackRepository;
        this.distributionRepository = distributionRepository;
        this.zonalAccountantRepository = zonalAccountantRepository;
    }

    // --- Dropdown/Helper Methods with Caching ---
    @Cacheable("academicYears")
    public List<AcademicYear> getAllAcademicYears() {
        return academicYearRepository.findAll();
    }

    @Cacheable("states")
    public List<State> getAllStates() {
        // Assumption: State entity has a field named 'is_active' or similar.
        return stateRepository.findByStatus(1);
    }

    @Cacheable(cacheNames = "citiesByState", key = "#stateId")
    public List<City> getCitiesByState(int stateId) {
        final int ACTIVE_STATUS = 1;
        return cityRepository.findByDistrictStateStateIdAndStatus(stateId, ACTIVE_STATUS);
    }

    @Cacheable(cacheNames = "zonesByCity", key = "#cityId")
    public List<Zone> getZonesByCity(int cityId) {
        return zoneRepository.findByCityCityId(cityId);
    }

    @Cacheable(cacheNames = "employeesByZone", key = "#zoneId")
    @Transactional(readOnly = true) // Recommended for lazy loading
    public List<EmployeesDto> getEmployeesByZone(int zoneId) {
        // Fetch only active zonal accountants (ZonalAccountant.isActive == 1)
        List<ZonalAccountant> activeAccountants = zonalAccountantRepository.findByZoneZoneIdAndIsActive(zoneId, 1);

        // Map and filter: Only include if Employee.isActive == 1
        return activeAccountants.stream().map(this::mapToEmployeeDto).filter(Objects::nonNull) // Skip null/inactive
                                                                                                // employees
                .collect(Collectors.toList());
    }

    // Helper: Maps only if employee is active (filters but doesn't include in DTO)
    private EmployeesDto mapToEmployeeDto(ZonalAccountant accountant) {
        var employee = accountant.getEmployee();
        // Filter: Skip if employee null or inactive
        if (employee == null || employee.getIsActive() == null || employee.getIsActive() != 1) {
            return null;
        }
        // Map without isActive
        return new EmployeesDto(employee.getEmp_id(), employee.getFirst_name(), employee.getLast_name(),
                employee.getPrimary_mobile_no());
    }

    @Transactional
    public void saveDistribution(@NonNull DistributionRequestDTO request) {

        validateEmployeeExists(request.getCreatedBy(), "Issuer");

        List<Distribution> overlappingDists = distributionRepository.findOverlappingDistributions(
                request.getAcademicYearId(), request.getAppStartNo(), request.getAppEndNo());

        System.out.println("DEBUG: saveDistribution - Sender ID: " + request.getCreatedBy() +
                ", Overlapping Distributions Found: " + overlappingDists.size() +
                ", Range: " + request.getAppStartNo() + "-" + request.getAppEndNo());
        for (Distribution d : overlappingDists) {
            System.out.println("DEBUG: Overlap - Dist ID: " + d.getAppDistributionId() +
                    ", IssuedToEmpId: " + d.getIssued_to_emp_id() +
                    ", Range: " + d.getAppStartNo() + "-" + d.getAppEndNo());
        }

        // Track if sender's balance was already recalculated in handleOverlappingDistributions
        boolean senderBalanceRecalculated = false;
        if (!overlappingDists.isEmpty()) {
            senderBalanceRecalculated = handleOverlappingDistributions(overlappingDists, request);
            System.out.println("DEBUG: handleOverlappingDistributions returned senderRecalculated: " + senderBalanceRecalculated);
        }
       
        // CRITICAL FIX: Check if SENDER's own distribution overlaps with the new distribution
        // The sender might have a distribution where they are the receiver (issued_to_emp_id = sender)
        // that overlaps with what they're giving away. This needs to be split!
        // Query ALL overlapping distributions again (in case some were inactivated) and filter for sender
        List<Distribution> allOverlaps = distributionRepository.findOverlappingDistributions(
                request.getAcademicYearId(), request.getAppStartNo(), request.getAppEndNo());
       
        List<Distribution> senderOverlaps = allOverlaps.stream()
                .filter(d -> {
                    // Find distributions where the SENDER is the receiver (they hold these apps)
                    Integer holderId = d.getIssued_to_emp_id() != null ? d.getIssued_to_emp_id() : d.getIssued_to_pro_id();
                    boolean isSender = holderId != null && holderId.equals(request.getCreatedBy());
                    if (isSender) {
                        System.out.println("DEBUG: Found SENDER's distribution - Dist ID: " + d.getAppDistributionId() +
                                ", Range: " + d.getAppStartNo() + "-" + d.getAppEndNo() +
                                ", IssuedToEmpId: " + d.getIssued_to_emp_id());
                    }
                    return isSender;
                })
                .toList();
       
        if (!senderOverlaps.isEmpty()) {
            System.out.println("DEBUG: Found " + senderOverlaps.size() + " SENDER's own distributions that overlap - will split them");
           
            // Handle sender's overlapping distributions - this will create remainders and recalculate balance
            boolean senderRecalc = handleOverlappingDistributions(senderOverlaps, request);
            if (senderRecalc) {
                senderBalanceRecalculated = true;
                System.out.println("DEBUG: Sender's distribution was split and balance recalculated");
            }
        } else {
            System.out.println("DEBUG: No SENDER's distributions found that overlap - sender might be Admin/CO or have no Distribution records");
        }

        // Handle multiple ZonalAccountant records for the same employee
        List<ZonalAccountant> receiverList = zonalAccountantRepository.findByEmployeeEmpId(request.getIssuedToEmpId());
        if (receiverList.isEmpty()) {
            throw new RuntimeException("Receiver not found for Employee ID: " + request.getIssuedToEmpId());
        }
       
        // Prefer the one matching the requested zone_id, otherwise take the first (most recent)
        ZonalAccountant receiver = receiverList.stream()
                .filter(za -> za.getZone() != null && za.getZone().getZoneId() == request.getZoneId())
                .findFirst()
                .orElse(receiverList.get(0));
       
        if (receiverList.size() > 1) {
            System.out.println("WARNING: Multiple active ZonalAccountant records found for Employee ID: " +
                    request.getIssuedToEmpId() + ". Total records: " + receiverList.size() + ". Selected one with zone_id: " +
                    (receiver.getZone() != null ? receiver.getZone().getZoneId() : "null"));
        }

        // Validate receiver has required relationships loaded
        if (receiver.getZone() == null) {
            throw new RuntimeException("Receiver Zone information is missing for Employee ID: " + request.getIssuedToEmpId());
        }

        // Optional: You can log a warning if the Zone doesn't match what the UI sent
        if (receiver.getZone().getZoneId() != request.getZoneId()) {
            System.out.println("WARNING: UI sent Zone " + request.getZoneId() + " but User "
                    + request.getIssuedToEmpId() + " is actually in Zone " + receiver.getZone().getZoneId());
        }

        if (receiver.getIsActive() != 1) {
            throw new RuntimeException("Transaction Failed: The selected Receiver is Inactive.");
        }

        Distribution newDistribution = new Distribution();
        mapDtoToDistribution(newDistribution, request); // Helper to map basic fields (State, Zone, Dates, etc.)

        // LOGIC: Determine where to save the ID (Employee Column vs PRO Column)
        if (receiver.getEmployee() != null) {
            // It's an Employee (e.g., DGM)
            newDistribution.setIssued_to_emp_id(receiver.getEmployee().getEmp_id());
            newDistribution.setIssued_to_pro_id(null);
        } else if (receiver.getCampus() != null) {
            // It's a PRO (Branch)
            newDistribution.setIssued_to_pro_id(receiver.getCampus().getCampusId());
            newDistribution.setIssued_to_emp_id(null);
        } else {
            throw new RuntimeException("Invalid Receiver: No Employee or Campus linked to this Zonal Accountant.");
        }

        // Set Fee/Amount
        newDistribution.setAmount(request.getApplication_Amount());

        // Save to DB
        Distribution savedDist = distributionRepository.saveAndFlush(newDistribution);

        // CRITICAL: Flush any pending remainders created in handleOverlappingDistributions
        // This ensures they are visible when rebuilding the sender's balance
        distributionRepository.flush();

        // A. Update SENDER's Balance (Reduce - because they gave away applications)
        // CRITICAL: ALWAYS recalculate sender's balance, regardless of overlaps
        // The sender gave away apps, so their balance MUST be updated
        // Even if there was an overlap for the receiver, the sender's balance still needs updating
        System.out.println("DEBUG: ALWAYS recalculating SENDER's balance - Employee: " + request.getCreatedBy() +
                ", senderBalanceRecalculated: " + senderBalanceRecalculated);
       
        // Flush first to ensure we see latest data including any remainders
        distributionRepository.flush();
       
        // Find what the sender actually holds to get correct type/amount
        List<Distribution> senderHoldings = distributionRepository.findActiveHoldingsForEmp(
                request.getCreatedBy(), request.getAcademicYearId());
       
        System.out.println("DEBUG: Sender holdings found: " + senderHoldings.size() + " for Employee: " + request.getCreatedBy());
       
        if (senderHoldings.isEmpty()) {
            // Sender has no holdings as receiver - they might be Admin/CO distributing from master allocation
            // OR they gave away all their apps
            // CRITICAL: Still recalculate balance - this will check AdminApp and create/update balance accordingly
            System.out.println("DEBUG: Sender has no holdings - checking if Admin/CO or recalculating balance");
            recalculateBalanceForEmployee(request.getCreatedBy(), request.getAcademicYearId(),
                    request.getStateId(), request.getIssuedByTypeId(),
                    request.getCreatedBy(), request.getApplication_Amount());
        } else {
            // Group by amount and recalculate for each amount the sender holds
            senderHoldings.stream()
                    .filter(d -> d.getAmount() != null)
                    .collect(java.util.stream.Collectors.groupingBy(Distribution::getAmount))
                    .forEach((amount, dists) -> {
                        // Use the type from the first distribution with this amount
                        Distribution firstDist = dists.get(0);
                        System.out.println("DEBUG: Recalculating sender balance for amount: " + amount +
                                ", Type: " + firstDist.getIssuedToType().getAppIssuedId() +
                                ", Holdings count: " + dists.size());
                        recalculateBalanceForEmployee(request.getCreatedBy(), request.getAcademicYearId(),
                                request.getStateId(), firstDist.getIssuedToType().getAppIssuedId(),
                                request.getCreatedBy(), amount);
                    });
        }
       
        // CRITICAL: Flush balance updates to ensure they're persisted
        balanceTrackRepository.flush();
        System.out.println("DEBUG: Sender balance recalculation completed for Employee: " + request.getCreatedBy());

        // B. Update Receiver's Balance (Increase - because they received applications)
        if (savedDist.getIssued_to_emp_id() != null) {
            addStockToReceiver(savedDist.getIssued_to_emp_id(), request.getAcademicYearId(),
                    request.getIssuedToTypeId(), request.getCreatedBy(), request.getApplication_Amount(),
                    request.getAppStartNo(), // Pass Start
                    request.getAppEndNo(), // Pass End
                    request.getRange() // Pass Count
            );
        }
    }

    @Transactional
    public void updateDistribution(int distributionId, @NonNull DistributionRequestDTO request) {

        // 1. Fetch Existing
        validateEmployeeExists(request.getCreatedBy(), "Issuer");
        Distribution existingDist = distributionRepository.findById(distributionId)
                .orElseThrow(() -> new RuntimeException("Record not found"));

        Float originalAmount = existingDist.getAmount(); // Keep amount!

        // 2. Resolve New Receiver (Zone Service always targets Employee column)
        // Handle multiple ZonalAccountant records for the same employee
        List<ZonalAccountant> receiverList = zonalAccountantRepository.findByEmployeeEmpId(request.getIssuedToEmpId());
        if (receiverList.isEmpty()) {
            throw new RuntimeException("New Receiver not found for Employee ID: " + request.getIssuedToEmpId());
        }
       
        // Prefer the one matching the requested zone_id, otherwise take the first (most recent)
        ZonalAccountant newReceiver = receiverList.stream()
                .filter(za -> za.getZone() != null && za.getZone().getZoneId() == request.getZoneId())
                .findFirst()
                .orElse(receiverList.get(0));
       
        if (receiverList.size() > 1) {
            System.out.println("WARNING: Multiple active ZonalAccountant records found for Employee ID: " +
                    request.getIssuedToEmpId() + ". Total records: " + receiverList.size() + ". Selected one with zone_id: " +
                    (newReceiver.getZone() != null ? newReceiver.getZone().getZoneId() : "null"));
        }

        // Determine Target ID (Logic: always grab the emp_id, even for campuses)
        Integer newTargetId;
        if (newReceiver.getEmployee() != null) {
            newTargetId = newReceiver.getEmployee().getEmp_id();
        } else {
            newTargetId = campusProViewRepository.findEmployeeIdsByCampusId(newReceiver.getCampus().getCampusId())
                    .stream().findFirst().orElseThrow(() -> new RuntimeException("No valid ID found for Campus"));
        }

        // 3. Inactivate Old
        existingDist.setIsActive(0);
        distributionRepository.saveAndFlush(existingDist);

        // 4. Create New
        Distribution newDist = new Distribution();
        mapDtoToDistribution(newDist, request);

        newDist.setIssued_to_emp_id(newTargetId);
        newDist.setIssued_to_pro_id(null); // Zone service keeps this null
        newDist.setAmount(originalAmount); // Preserve Amount

        distributionRepository.saveAndFlush(newDist);

        // 5. Handle Remainders
        int oldStart = (int) existingDist.getAppStartNo();
        int oldEnd = (int) existingDist.getAppEndNo();

        if (oldStart != request.getAppStartNo() || oldEnd != request.getAppEndNo()) {
            if (oldStart < request.getAppStartNo()) {
                createAndSaveRemainder(existingDist, oldStart, request.getAppStartNo() - 1);
            }
            if (oldEnd > request.getAppEndNo()) {
                createAndSaveRemainder(existingDist, request.getAppEndNo() + 1, oldEnd);
            }
        }

        // 6. Recalculate Balances
        int acYear = existingDist.getAcademicYear().getAcdcYearId();
        int stateId = existingDist.getState().getStateId();

        // A. Issuer
        recalculateBalanceForEmployee(request.getCreatedBy(), acYear, stateId, request.getIssuedByTypeId(),
                request.getCreatedBy(), originalAmount);

        // B. New Receiver
        recalculateBalanceForEmployee(newTargetId, acYear, stateId, request.getIssuedToTypeId(), request.getCreatedBy(),
                originalAmount);

        // C. Old Receiver (If changed)
        Integer oldId = existingDist.getIssued_to_emp_id();
        if (oldId != null && (!Objects.equals(oldId, newTargetId)
                || (oldStart != request.getAppStartNo() || oldEnd != request.getAppEndNo()))) {
            recalculateBalanceForEmployee(oldId, acYear, stateId, existingDist.getIssuedToType().getAppIssuedId(),
                    request.getCreatedBy(), originalAmount);
        }
    }

    private void createAndSaveRemainder(Distribution originalDist, int start, int end) {
        Distribution remainder = new Distribution();

        // Copy standard fields
        remainder.setAcademicYear(originalDist.getAcademicYear());
        remainder.setState(originalDist.getState());
        remainder.setCity(originalDist.getCity());
        remainder.setZone(originalDist.getZone());
        remainder.setDistrict(originalDist.getDistrict());
        remainder.setIssuedByType(originalDist.getIssuedByType());
        remainder.setIssuedToType(originalDist.getIssuedToType());
        remainder.setCreated_by(originalDist.getCreated_by());
        remainder.setIssueDate(originalDist.getIssueDate() != null ? originalDist.getIssueDate() : LocalDateTime.now());
        remainder.setAmount(originalDist.getAmount());
        remainder.setIssued_to_emp_id(originalDist.getIssued_to_emp_id());
        remainder.setIssued_to_pro_id(originalDist.getIssued_to_pro_id());

        // Set New Range
        remainder.setAppStartNo(start);
        remainder.setAppEndNo(end);
        remainder.setTotalAppCount((end - start) + 1);
        remainder.setIsActive(1); // Active

        distributionRepository.saveAndFlush(remainder);
    }

    private boolean handleOverlappingDistributions(List<Distribution> overlappingDists, DistributionRequestDTO request) {
        int reqStart = request.getAppStartNo();
        int reqEnd = request.getAppEndNo();
        boolean senderRecalculated = false;

        for (Distribution oldDist : overlappingDists) {

            // 1. Identify the "Old Holder" (The Victim)
            Integer oldHolderId;
            boolean isPro = false;

            if (oldDist.getIssued_to_pro_id() != null) {
                oldHolderId = oldDist.getIssued_to_pro_id();
                isPro = true;
            } else if (oldDist.getIssued_to_emp_id() != null) {
                oldHolderId = oldDist.getIssued_to_emp_id();
                isPro = false;
            } else {
                continue;
            }

            // [DELETED] The check that skipped self-updates is gone.
            // We process EVERY overlap to ensure the old record is deactivated.

            // 2. Inactivate OLD (Soft Delete)
            oldDist.setIsActive(0);

            // CRITICAL: Flush so the database knows this is now 0
            distributionRepository.saveAndFlush(oldDist);

            int oldStart = oldDist.getAppStartNo();
            int oldEnd = oldDist.getAppEndNo();

            // 3. Create "Before" Split (Remainder)
            if (oldStart < reqStart) {
                createAndSaveRemainder(oldDist, oldStart, reqStart - 1);
            }

            // 4. Create "After" Split (Remainder)
            if (oldEnd > reqEnd) {
                System.out.println("DEBUG: Creating AFTER remainder: " + (reqEnd + 1) + " to " + oldEnd + " for Employee: " + oldHolderId);
                createAndSaveRemainder(oldDist, reqEnd + 1, oldEnd);
            }

            // 5. Recalculate Balance for the OLD HOLDER (The Victim)
            // We use the Old Distribution's metadata (State, Type, Amount)
            int acYear = request.getAcademicYearId();
            int stateId = oldDist.getState().getStateId();
            int typeId = oldDist.getIssuedToType().getAppIssuedId();
            int modifierId = request.getCreatedBy();
            Float amount = oldDist.getAmount(); // Keep Original Amount

            System.out.println("DEBUG: Recalculating balance for Employee: " + oldHolderId +
                    ", Type: " + typeId + ", Amount: " + amount + ", Sender: " + request.getCreatedBy());

            // CRITICAL: Flush remainders before recalculating balance to ensure they're visible
            distributionRepository.flush();
           
            recalculateBalanceForEmployee(oldHolderId, acYear, stateId, typeId, modifierId, amount);
           
            // CRITICAL: Flush balance updates to ensure they're persisted
            balanceTrackRepository.flush();
           
            // Track if sender's balance was recalculated
            if (oldHolderId != null && oldHolderId.equals(request.getCreatedBy())) {
                senderRecalculated = true;
                System.out.println("DEBUG: Sender's balance was recalculated in handleOverlappingDistributions");
            }
        }
       
        return senderRecalculated;
    }

    private void recalculateBalanceForEmployee(int employeeId, int academicYearId, int stateId, int typeId,
            int createdBy, Float amount) {

        // 1. CHECK: Is this a CO/Admin? (Check Master Table)
        // We convert Float to Float for the repo call
        Optional<AdminApp> adminApp = adminAppRepository.findByEmpAndYearAndAmount(employeeId, academicYearId, amount);

        if (adminApp.isPresent()) {
            // --- CASE A: CO / ADMIN (The Source) ---
            // Logic: Master Allocation - Total Distributed

            AdminApp master = adminApp.get();

            // Admins usually have 1 giant balance row, so we fetch/create just one.
            List<BalanceTrack> balances = balanceTrackRepository.findActiveBalancesByEmpAndAmount(academicYearId,
                    employeeId, amount);

            BalanceTrack balance;
            if (balances.isEmpty()) {
                balance = createNewBalanceTrack(employeeId, academicYearId, typeId, createdBy);
                balance.setAmount(amount);
            } else {
                balance = balances.get(0); // Use the existing one
            }

            // Calculate Total Distributed by Admin
            int totalDistributed = distributionRepository
                    .sumTotalAppCountByCreatedByAndAmount(employeeId, academicYearId, amount).orElse(0);

            // CRITICAL FIX: Calculate app_from based on what they've distributed
            // If they've distributed from the beginning, app_from should be the next available number
            // Otherwise, use the master start
            Optional<Integer> maxDistributedEnd = distributionRepository
                    .findMaxAppEndNoByCreatedByAndAmount(employeeId, academicYearId, amount);
           
            int calculatedAppFrom;
            if (maxDistributedEnd.isPresent() && maxDistributedEnd.get() >= master.getAppFromNo()) {
                // They've distributed from the beginning - app_from should be next available
                calculatedAppFrom = maxDistributedEnd.get() + 1;
                System.out.println("DEBUG: Admin/CO distributed up to " + maxDistributedEnd.get() +
                        ", setting app_from to " + calculatedAppFrom);
            } else {
                // They haven't distributed from the beginning yet - use master start
                calculatedAppFrom = master.getAppFromNo();
                System.out.println("DEBUG: Admin/CO hasn't distributed from beginning, using master start: " + calculatedAppFrom);
            }
           
            // Ensure app_from doesn't exceed master end
            if (calculatedAppFrom > master.getAppToNo()) {
                calculatedAppFrom = master.getAppToNo() + 1; // All apps distributed
            }

            // Update Logic
            balance.setAppFrom(calculatedAppFrom); // Use calculated start (next available or master start)
            balance.setAppTo(master.getAppToNo());
            balance.setAppAvblCnt(master.getTotalApp() - totalDistributed);

            // CRITICAL: Save and flush to ensure the balance update is persisted
            balanceTrackRepository.saveAndFlush(balance);

        } else {
            // --- CASE B: ZONE & DGM (The Intermediaries) ---
            // They do NOT have a master table. They rely purely on what they HOLD.
            // We call the helper to rebuild their balance rows to match their holdings.

            rebuildBalancesFromDistributions(employeeId, academicYearId, typeId, createdBy, amount);
        }
    }

    private void rebuildBalancesFromDistributions(int empId, int acYearId, int typeId, int createdBy, Float amount) {

        // CRITICAL: Clear persistence context to ensure we see the latest data including remainders
        // This is especially important after handleOverlappingDistributions creates remainders
        distributionRepository.flush();

        // 1. Get ALL Active Distributions currently HELD by this user (without amount filter to avoid Float comparison issues)
        // Then filter by amount in Java for safe Float comparison
        // This includes remainders created when they gave away part of their range
        List<Distribution> allHoldings = distributionRepository.findActiveHoldingsForEmp(empId, acYearId);
       
        System.out.println("DEBUG: rebuildBalancesFromDistributions - Employee: " + empId + ", All Holdings Found: " + allHoldings.size());
       
        // 2. Filter by Amount in Java (Safe Float Comparison - handles precision issues)
        List<Distribution> holdings = allHoldings.stream()
                .filter(d -> d.getAmount() != null && Math.abs(d.getAmount() - amount) < 0.01)
                .sorted((d1, d2) -> Long.compare(d1.getAppStartNo(), d2.getAppStartNo()))
                .toList();

        System.out.println("DEBUG: rebuildBalancesFromDistributions - Employee: " + empId + ", Holdings After Amount Filter: " + holdings.size() + ", Amount: " + amount);
        for (Distribution d : holdings) {
            System.out.println("DEBUG: Holding - Start: " + d.getAppStartNo() + ", End: " + d.getAppEndNo() + ", Amount: " + d.getAmount());
        }

        // 3. Get CURRENT Active Balance Rows for this amount
        List<BalanceTrack> currentBalances = balanceTrackRepository.findActiveBalancesByEmpAndAmount(acYearId, empId,
                amount);

        System.out.println("DEBUG: rebuildBalancesFromDistributions - Employee: " + empId + ", Current Balance Rows: " + currentBalances.size());

        // 4. STRATEGY: Soft Delete OLD rows, Insert NEW rows
        // This effectively "Refreshes" the balance to match reality.

        // A. Mark old rows as inactive
        for (BalanceTrack b : currentBalances) {
            b.setIsActive(0);
            balanceTrackRepository.save(b);
            System.out.println("DEBUG: Deactivated old balance row ID: " + b.getAppBalanceTrkId());
        }

        // B. Create new rows for every active distribution held
        // CRITICAL: appFrom MUST be set from dist.getAppStartNo() to ensure correct range
        // When sender gives away apps from the beginning, remainders are created with updated app_from
        // This ensures the balance reflects what they still hold
        if (holdings.isEmpty()) {
            System.out.println("WARNING: No holdings found for Employee " + empId + " with amount " + amount + ". Balance will be 0.");
        } else {
            for (Distribution dist : holdings) {
                BalanceTrack nb = createNewBalanceTrack(empId, acYearId, typeId, createdBy);

                nb.setAmount(amount);
                // CRITICAL FIX: appFrom MUST be set from the distribution's start number
                // This is the key fix - when remainders are created (e.g., 2,875,103-2,877,502),
                // appFrom will be correctly set to 2,875,103 instead of the old 2,875,002
                nb.setAppFrom((int) dist.getAppStartNo());
                nb.setAppTo((int) dist.getAppEndNo());
                nb.setAppAvblCnt(dist.getTotalAppCount());

                // CRITICAL: Save and flush to ensure the balance row is persisted immediately
                BalanceTrack saved = balanceTrackRepository.saveAndFlush(nb);
                System.out.println("DEBUG: Created new balance row ID: " + saved.getAppBalanceTrkId() +
                        ", AppFrom: " + saved.getAppFrom() + ", AppTo: " + saved.getAppTo() +
                        ", EmpId: " + empId);
            }
        }
       
        // C. Final flush to ensure all balance updates are persisted
        balanceTrackRepository.flush();
       
        // D. If no holdings exist, all balance rows have been deactivated (balance is now 0)
        // This is correct behavior - user has no applications left
    }

    private Distribution createRemainderDistribution(Distribution originalDist, int receiverId) {
        Distribution remainderDistribution = new Distribution();
        // Copy most fields from the original distribution
        mapDtoToDistribution(remainderDistribution, createDtoFromDistribution(originalDist));

        // Set specific fields for the remainder
        remainderDistribution.setIssued_to_emp_id(receiverId); // Stays with the OLD receiver
        remainderDistribution.setIsActive(1);

        // Note: The range and count will be set by the caller (updateDistribution)
        return remainderDistribution;
    }

    private void mapDtoToDistribution(Distribution d, DistributionRequestDTO req) {
        d.setAcademicYear(academicYearRepository.findById(req.getAcademicYearId()).orElseThrow());
        d.setState(stateRepository.findById(req.getStateId()).orElseThrow());
        d.setZone(zoneRepository.findById(req.getZoneId()).orElseThrow());
        d.setIssuedByType(appIssuedTypeRepository.findById(req.getIssuedByTypeId()).orElseThrow());
        d.setIssuedToType(appIssuedTypeRepository.findById(req.getIssuedToTypeId()).orElseThrow());
        City city = cityRepository.findById(req.getCityId()).orElseThrow();
        d.setCity(city);
        d.setDistrict(city.getDistrict());
        d.setAmount(req.getApplication_Amount());
        d.setIssueDate(LocalDateTime.now());
        d.setIssued_to_emp_id(req.getIssuedToEmpId());
        d.setCreated_by(req.getCreatedBy());
        d.setAppStartNo(req.getAppStartNo());
        d.setAppEndNo(req.getAppEndNo());
        d.setTotalAppCount(req.getRange());
        d.setIsActive(1);
    }

    private DistributionRequestDTO createDtoFromDistribution(Distribution dist) {
        DistributionRequestDTO dto = new DistributionRequestDTO();
        dto.setAcademicYearId(dist.getAcademicYear().getAcdcYearId());
        dto.setStateId(dist.getState().getStateId());
        dto.setCityId(dist.getCity().getCityId());
        dto.setZoneId(dist.getZone().getZoneId());
        dto.setIssuedByTypeId(dist.getIssuedByType().getAppIssuedId());
        dto.setIssuedToTypeId(dist.getIssuedToType().getAppIssuedId());
        dto.setIssuedToEmpId(dist.getIssued_to_emp_id());
        dto.setApplication_Amount(dist.getAmount());
        dto.setAppStartNo(dist.getAppStartNo());
        dto.setAppEndNo(dist.getAppEndNo());
        dto.setRange(dist.getTotalAppCount());
//      dto.setIssueDate(dist.getIssueDate());
        dto.setCreatedBy(dist.getCreated_by());
        return dto;
    }

    private BalanceTrack createNewBalanceTrack(int employeeId, int academicYearId, int typeId, int createdBy) {
        BalanceTrack nb = new BalanceTrack();
        nb.setEmployee(employeeRepository.findById(employeeId).orElseThrow());
        nb.setAcademicYear(academicYearRepository.findById(academicYearId).orElseThrow());
        nb.setIssuedByType(appIssuedTypeRepository.findById(typeId).orElseThrow());

        nb.setIssuedToProId(null); // Strict Validation: It's an Employee
        nb.setAppAvblCnt(0);
        nb.setIsActive(1);
        nb.setCreatedBy(createdBy);
        return nb;
    }

    private void validateEmployeeExists(int employeeId, String role) {
        if (employeeId <= 0 || !employeeRepository.existsById(employeeId)) {
            throw new IllegalArgumentException(role + " employee not found or invalid ID: " + employeeId);
        }
    }

    // This is SPECIFICALLY for adding new stock to a Receiver (Zone/DGM)
    private void addStockToReceiver(int employeeId, int academicYearId, int typeId, int createdBy, Float amount,
            int newStart, int newEnd, int newCount) {

        // 1. Calculate the "Target End" (The number immediately before the new batch)
        int targetEnd = newStart - 1;

        // 2. Check if we can MERGE with an existing row
        Optional<BalanceTrack> mergeableRow = balanceTrackRepository.findMergeableRowForEmployee(academicYearId,
                employeeId, amount, targetEnd);

        if (mergeableRow.isPresent()) {
            // SCENARIO: CONTIGUOUS (1-50 exists, adding 51-100)
            BalanceTrack existing = mergeableRow.get();

            // Update the existing row
            existing.setAppTo(newEnd); // Extend the range (50 -> 100)
            existing.setAppAvblCnt(existing.getAppAvblCnt() + newCount); // Add count

            balanceTrackRepository.save(existing);
        } else {
            // SCENARIO: DISTURBED / GAP (1-50 exists, adding 101-150)
            // Create a BRAND NEW row
            BalanceTrack newRow = createNewBalanceTrack(employeeId, academicYearId, typeId, createdBy);
            newRow.setEmployee(employeeRepository.findById(employeeId).orElseThrow());
            newRow.setIssuedToProId(null);
            newRow.setAmount(amount);
            newRow.setIsActive(1);

            // Set the specific range for this packet
            newRow.setAppFrom(newStart);
            newRow.setAppTo(newEnd);
            newRow.setAppAvblCnt(newCount);

            balanceTrackRepository.save(newRow);
        }
    }
}
