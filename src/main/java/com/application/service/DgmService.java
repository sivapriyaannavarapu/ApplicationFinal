package com.application.service;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
 
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.application.dto.AppSeriesDTO;
import com.application.dto.FormSubmissionDTO;
import com.application.dto.GenericDropdownDTO;
import com.application.dto.LocationAutoFillDTO;
import com.application.entity.AdminApp;
import com.application.entity.BalanceTrack;
import com.application.entity.Campus;
import com.application.entity.City;
import com.application.entity.Dgm;
import com.application.entity.Distribution;
import com.application.entity.District;
import com.application.entity.UserAdminView;
import com.application.repository.AcademicYearRepository;
import com.application.repository.AdminAppRepository;
import com.application.repository.AppIssuedTypeRepository;
import com.application.repository.BalanceTrackRepository;
import com.application.repository.CampusRepository;
import com.application.repository.CityRepository;
import com.application.repository.DgmRepository;
import com.application.repository.DistributionRepository;
import com.application.repository.EmployeeRepository;
import com.application.repository.UserAdminViewRepository;
import com.application.repository.ZonalAccountantRepository;
import com.application.repository.ZoneRepository;
 
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
 
@Service
@RequiredArgsConstructor
public class DgmService {
 
    private final AcademicYearRepository academicYearRepository;
    private final CityRepository cityRepository;
    private final ZoneRepository zoneRepository;
    private final CampusRepository campusRepository;
    private final AppIssuedTypeRepository appIssuedTypeRepository;
    private final DistributionRepository distributionRepository;
    private final EmployeeRepository employeeRepository;
    private final BalanceTrackRepository balanceTrackRepository;
    private final DgmRepository dgmRepository;
    private final UserAdminViewRepository userAdminViewRepository;
    private final ZonalAccountantRepository zonalAccountantRepository;
    private final AdminAppRepository adminAppRepository;
 
    // --- Dropdown and Helper Methods with Caching ---
//    @Cacheable("academicYears")
    public List<GenericDropdownDTO> getAllAcademicYears() {
        return academicYearRepository.findAll().stream()
                .map(year -> new GenericDropdownDTO(year.getAcdcYearId(), year.getAcademicYear()))
                .collect(Collectors.toList());
    }
 
    @Cacheable("cities")
    public List<GenericDropdownDTO> getAllCities() {
        final int ACTIVE_STATUS = 1;
 
        return cityRepository.findByStatus(ACTIVE_STATUS).stream()
                .map(city -> new GenericDropdownDTO(city.getCityId(), city.getCityName())).collect(Collectors.toList());
    }
 
//    @Cacheable(cacheNames = "zonesByCity", key = "#cityId")
    public List<GenericDropdownDTO> getZonesByCityId(int cityId) {
        return zoneRepository.findByCityCityId(cityId).stream()
                .map(zone -> new GenericDropdownDTO(zone.getZoneId(), zone.getZoneName())).collect(Collectors.toList());
    }
 
    @Cacheable(cacheNames = "campusesByZone", key = "#zoneId")
    public List<GenericDropdownDTO> getCampusesByZoneId(int zoneId) {
        // Call the new repository method
        return campusRepository.findActiveCampusesByZoneId(zoneId).stream()
                // .distinct() might be useful here to ensure unique campuses if a campus is
                // linked to multiple active zonal accountants in the same zone
                .distinct().map(campus -> new GenericDropdownDTO(campus.getCampusId(), campus.getCampusName()))
                .collect(Collectors.toList());
    }
 
    @Cacheable(cacheNames = "campusforzonalaccountant", key = "#empId")
    public List<GenericDropdownDTO> getCampusesByEmployeeId(int empId) {
        List<Integer> zoneIds = zonalAccountantRepository.findZoneIdByEmployeeId(empId);
        if (zoneIds == null || zoneIds.isEmpty()) {
            return Collections.emptyList();
        }
 
        return zoneIds.stream().flatMap(zoneId -> getCampusesByZoneId(zoneId).stream()).distinct()
                .collect(Collectors.toList());
    }
   
    public List<GenericDropdownDTO> getCampusesByEmployeeIdAndCategory(int empId, String category) {
 
        List<Integer> zoneIds = zonalAccountantRepository.findZoneIdByEmployeeId(empId);
 
        if (zoneIds == null || zoneIds.isEmpty()) {
            return Collections.emptyList();
        }
 
        List<GenericDropdownDTO> allCampuses =
                zoneIds.stream()
                        .flatMap(zoneId -> getCampusesByZoneId(zoneId).stream())
                        .distinct()
                        .collect(Collectors.toList());
 
        // ============================
        // 🔥 CATEGORY FILTERING LOGIC
        // ============================
        if (category == null || category.isEmpty()) {
            return allCampuses; // no filter applied
        }
 
        String cat = category.trim().toLowerCase();
 
        return allCampuses.stream()
                .filter(c -> {
                    Campus campus = campusRepository.findById(c.getId()).orElse(null);
                    if (campus == null || campus.getBusinessType() == null) return false;
 
                    String type = campus.getBusinessType().getBusinessTypeName().toLowerCase();
 
                    if (cat.equals("school")) {
                        return type.contains("school");
                    } else if (cat.equals("college")) {
                        return type.contains("college");
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }
 
 
    public List<GenericDropdownDTO> getActiveCampusesByEmpId(Integer empId) {
        List<Dgm> dgms = dgmRepository.findByEmpId(empId);
 
        // Map to GenericDropdownDTO — filtering only active campuses
        return dgms.stream().filter(
                d -> d.getCampus() != null && d.getCampus().getIsActive() != null && d.getCampus().getIsActive() == 1)
                .map(d -> new GenericDropdownDTO(d.getCampus().getCampusId(), d.getCampus().getCampusName()))
                .collect(Collectors.toList());
    }
   
    public List<GenericDropdownDTO> getActiveCampusesByEmpIdAndCategory(Integer empId, String category) {
 
        List<Dgm> dgms = dgmRepository.findByEmpId(empId);
 
        // Step 1 → Get all active campuses for this DGM
        List<GenericDropdownDTO> campusList =
                dgms.stream()
                    .filter(d -> d.getCampus() != null &&
                                 d.getCampus().getIsActive() != null &&
                                 d.getCampus().getIsActive() == 1)
                    .map(d -> new GenericDropdownDTO(
                            d.getCampus().getCampusId(),
                            d.getCampus().getCampusName()
                    ))
                    .collect(Collectors.toList());
 
        // Step 2 → If no category, return same as before
        if (category == null || category.trim().isEmpty()) {
            return campusList;
        }
 
        // Step 3 → Filter by category
        String cat = category.trim().toLowerCase();
 
        return campusList.stream()
                .filter(c -> matchBusinessType(c.getId(), cat))   // reused helper from earlier
                .collect(Collectors.toList());
    }
 
 
    public List<GenericDropdownDTO> getActiveCampusesByEmployeeId(int empId) {
        List<Integer> zoneIds = zonalAccountantRepository.findZoneIdByEmployeeId(empId);
        if (zoneIds == null || zoneIds.isEmpty()) {
            return Collections.emptyList();
        }
 
        // Step 2: Get campus IDs for all zones
        List<Integer> campusIds = dgmRepository.findCampusIdsByZoneIds(zoneIds);
        if (campusIds == null || campusIds.isEmpty()) {
            return Collections.emptyList();
        }
 
        // Step 3: Get only active campuses
        return campusRepository.findActiveCampusesByIds(campusIds);
    }
   
   
    public List<GenericDropdownDTO> getActiveCampusesByEmployeeIdAndCategory(int empId, String category) {
 
        // Step 1 → Find zones for this zonal accountant
        List<Integer> zoneIds = zonalAccountantRepository.findZoneIdByEmployeeId(empId);
        if (zoneIds == null || zoneIds.isEmpty()) {
            return Collections.emptyList();
        }
 
        // Step 2 → Get campuses mapped under DGM for those zones
        List<Integer> campusIds = dgmRepository.findCampusIdsByZoneIds(zoneIds);
        if (campusIds == null || campusIds.isEmpty()) {
            return Collections.emptyList();
        }
 
        // Step 3 → Get only active campuses (already returns GenericDropdownDTO)
        List<GenericDropdownDTO> campuses = campusRepository.findActiveCampusesByIds(campusIds);
 
        // Step 4 → If category not provided → return as is
        if (category == null || category.trim().isEmpty()) {
            return campuses;
        }
 
        // Step 5 → Filter by business type (school/college)
        String cat = category.trim().toLowerCase();
 
        return campuses.stream()
                .filter(c -> matchBusinessType(c.getId(), cat))
                .collect(Collectors.toList());
    }
   
    public List<GenericDropdownDTO> getDgmCampusesByZoneAndCategory(Integer zoneId, String category) {
        Integer valid = zonalAccountantRepository.validateZone(zoneId);
        if (valid == null || valid == 0) {
            return Collections.emptyList();
        }
        List<Campus> campuses = dgmRepository.findCampusesByZone(zoneId);
        if (campuses == null || campuses.isEmpty()) {
            return Collections.emptyList();
        }
        List<Campus> filtered = campuses.stream()
                .filter(c -> filterByCategory(c, category))
                .collect(Collectors.toList());
        return filtered.stream()
                .map(c -> new GenericDropdownDTO(c.getCampusId(), c.getCampusName()))
                .collect(Collectors.toList());
    }
   
    private boolean filterByCategory(Campus campus, String category) {
 
        if (category == null || category.isBlank()) {
            return true;
        }
 
        if (campus.getBusinessType() == null) {
            return false;
        }
 
        String type = campus.getBusinessType().getBusinessTypeName().toLowerCase();
 
        switch (category.toLowerCase()) {
            case "school":
                return type.contains("school");
            case "college":
                return type.contains("college");
            default:
                return true;
        }
    }
   
    private boolean matchBusinessType(Integer campusId, String category) {
 
        Campus campus = campusRepository.findById(campusId).orElse(null);
        if (campus == null || campus.getBusinessType() == null) {
            return false;
        }
 
        String businessName = campus.getBusinessType().getBusinessTypeName().toLowerCase();
 
        switch (category) {
            case "school":
                return businessName.contains("school");
            case "college":
                return businessName.contains("college");
            default:
                return true; // Others → no filter
        }
    }
 
 
//    @Cacheable("issuedToTypes")
    public List<GenericDropdownDTO> getAllIssuedToTypes() {
        return appIssuedTypeRepository.findAll().stream()
                .map(type -> new GenericDropdownDTO(type.getAppIssuedId(), type.getTypeName()))
                .collect(Collectors.toList());
    }
 
    @Cacheable(value = "mobileNumberByEmpId", key = "#empId")
    public String getMobileNumberByEmpId(int empId) {
        return employeeRepository.findMobileNoByEmpId(empId);
    }
 
    @Cacheable(cacheNames = "getDgmforCampus", key = "#campusId")
    public List<GenericDropdownDTO> getDgmEmployeesForCampus(int campusId) {
        // 1. Find the Campus by ID
        Optional<Campus> campusOptional = campusRepository.findById(campusId);
 
        if (campusOptional.isEmpty()) {
            return Collections.emptyList();
        }
 
        // 2. Get the Zone ID from the Campus
        Campus campus = campusOptional.get();
        int zoneId = campus.getZone().getZoneId();
 
        // 3. Find distinct DGM employees for that Zone, checking isActive = 1
        // Calling the updated repository method:
        return dgmRepository.findDistinctActiveEmployeesByZoneId(zoneId);
    }
   
public List<Double> getApplicationFees(int empId, int academicYearId) { // UPDATED SIGNATURE
       
        // 1. Check AdminApp table first (UPDATED CALL)
        List<Double> adminFees = adminAppRepository.findAmountsByEmpIdAndAcademicYear(empId, academicYearId);
 
        // 2. If AdminApp has data, convert to Double and return
        if (adminFees != null && !adminFees.isEmpty()) {
            return adminFees.stream()
                    .map(Double::valueOf)
                    .collect(Collectors.toList());
        }
 
        // 3. If AdminApp is empty, check BalanceTrack table (UPDATED CALL)
        List<Float> balanceFees = balanceTrackRepository.findAmountsByEmpIdAndAcademicYear(empId, academicYearId);
 
        if (balanceFees != null && !balanceFees.isEmpty()) {
            return balanceFees.stream()
                    .map(Double::valueOf)
                    .collect(Collectors.toList());
        }
 
        // 4. If both are empty, return an empty list
        return Collections.emptyList();
    }
 
 
public LocationAutoFillDTO getAutoPopulateData(int empId, String category) {
 
    // 1️⃣ Only apply logic when category = "school"
    if (!"school".equalsIgnoreCase(category)) {
        return null;  
    }
 
    // 2️⃣ Get active DGM record for employee
    Dgm dgm = dgmRepository
            .findActiveDgm(empId, 1)
            .orElse(null);
 
    if (dgm == null) {
        return null;
    }
 
    // 3️⃣ DISTRICT (direct from Dgm table)
    District district = dgm.getDistrict();
 
    Integer districtId   = district != null ? district.getDistrictId() : null;
    String districtName  = district != null ? district.getDistrictName() : null;
 
    // 4️⃣ CITY (via Campus)
    Campus campus = dgm.getCampus();
    City city = (campus != null) ? campus.getCity() : null;
 
    Integer cityId   = city != null ? city.getCityId() : null;
    String cityName  = city != null ? city.getCityName() : null;
 
    // 5️⃣ Return final DTO
    return new LocationAutoFillDTO(cityId, cityName, districtId, districtName);
}
   
 
 
//  public Optional<AppFromDTO> getAppFromByEmployeeAndYear(int employeeId, int academicYearId) {
//      return balanceTrackRepository.getAppFromByEmployeeAndAcademicYear(employeeId, academicYearId);
//
//      // OR if using the @Query method:
//      // return balanceTrackRepository.getAppFromByEmployeeAndAcademicYear(employeeId,
//      // academicYearId);
//  }
//
////  @Cacheable(cacheNames = "getAppRange", key = "{#academicYearId, #employeeId}")
//  public AppRangeDTO getAppRange(int empId, int academicYearId) {
//      // Fetch distribution data
//      AppDistributionDTO distDTO = distributionRepository
//              .findActiveAppRangeByEmployeeAndAcademicYear(empId, academicYearId).orElse(null);
//
//      // Fetch balance track data (now returns AppFromDTO with the ID)
//      AppFromDTO fromDTO = balanceTrackRepository.getAppFromByEmployeeAndAcademicYear(empId, academicYearId)
//              .orElse(null);
//
//      if (distDTO == null && fromDTO == null) {
//          return null;
//      }
//
//      // Merge results into a single DTO
//      Integer appStartNo = distDTO != null ? distDTO.getAppStartNo() : null;
//      Integer appEndNo = distDTO != null ? distDTO.getAppEndNo() : null;
//
//      // Extract fields from the updated AppFromDTO
//      Integer appFrom = fromDTO != null ? fromDTO.getAppFrom() : null;
//      Integer appBalanceTrkId = fromDTO != null ? fromDTO.getAppBalanceTrkId() : null; // Extracted new ID
//
//      // Use the updated AppRangeDTO constructor
//      return new AppRangeDTO(appStartNo, appEndNo, appFrom, appBalanceTrkId);
//  }
//
//  public AppRangeDTO getAppRange(int empId, int academicYearId, Integer cityId) { // Updated signature
//      // Fetch distribution data
//      AppDistributionDTO distDTO = distributionRepository
//              .findActiveAppRangeByEmployeeAndAcademicYear(empId, academicYearId, cityId) // Pass cityId
//              .orElse(null);
//
//      // Fetch balance track data (now returns AppFromDTO with the ID)
//      AppFromDTO fromDTO = balanceTrackRepository.getAppFromByEmployeeAndAcademicYear(empId, academicYearId)
//              .orElse(null);
//
//      if (distDTO == null && fromDTO == null) {
//          return null;
//      }
//
//      // Merge results into a single DTO
//      Integer appStartNo = distDTO != null ? distDTO.getAppStartNo() : null;
//      Integer appEndNo = distDTO != null ? distDTO.getAppEndNo() : null;
//
//      // Extract fields from the updated AppFromDTO
//      Integer appFrom = fromDTO != null ? fromDTO.getAppFrom() : null;
//      Integer appBalanceTrkId = fromDTO != null ? fromDTO.getAppBalanceTrkId() : null; // Extracted new ID
//
//      // Use the updated AppRangeDTO constructor
//      return new AppRangeDTO(appStartNo, appEndNo, appFrom, appBalanceTrkId);
//  }
 
//... inside DgmService class ...
 
// Updated Method to use Double - First check BalanceTrack, then AdminApp (for employees only)
public List<AppSeriesDTO> getActiveSeriesForReceiver(int receiverId, int academicYearId, Double amount, boolean isPro) {
   
    // 1. FIRST: Try to fetch Series List from BalanceTrack with academicYearId filter
    List<AppSeriesDTO> seriesList;
    if (isPro) {
        // For PRO: Only check BalanceTrack (AdminApp doesn't have PRO data)
        seriesList = balanceTrackRepository.findSeriesByProIdYearAndAmount(receiverId, academicYearId, amount);
       
        // If BalanceTrack has data, enrich with master info and return
        if (seriesList != null && !seriesList.isEmpty()) {
            // Fetch Master Info from AdminApp
            List<AdminApp> masterRecords = adminAppRepository.findMasterRecordByYearAndAmount(
                    academicYearId, amount);
 
            // Enrich DTOs with master info
            if (!masterRecords.isEmpty()) {
                AdminApp master = masterRecords.get(0);
                int mStart = master.getAppFromNo();
                int mEnd = master.getAppToNo();
 
                for (AppSeriesDTO dto : seriesList) {
                    dto.setMasterStartNo(mStart);
                    dto.setMasterEndNo(mEnd);
                }
            }
        }
        // Return BalanceTrack data or empty list for PRO
        return seriesList != null ? seriesList : new ArrayList<>();
       
    } else {
        // For Employee: Check BalanceTrack first, then fallback to AdminApp
        seriesList = balanceTrackRepository.findSeriesByEmpIdYearAndAmount(receiverId, academicYearId, amount);
 
        // 2. If BalanceTrack has data, enrich with master info and return
        if (seriesList != null && !seriesList.isEmpty()) {
            // Fetch Master Info from AdminApp with employeeId filter
            List<AdminApp> masterRecords = adminAppRepository.findMasterRecordByYearAndAmountAndEmployee(
                    receiverId, academicYearId, amount);
 
            // Enrich DTOs with master info
            if (!masterRecords.isEmpty()) {
                AdminApp master = masterRecords.get(0);
                int mStart = master.getAppFromNo();
                int mEnd = master.getAppToNo();
 
                for (AppSeriesDTO dto : seriesList) {
                    dto.setMasterStartNo(mStart);
                    dto.setMasterEndNo(mEnd);
                }
            }
            return seriesList;
        }
 
        // 3. If BalanceTrack is empty, fetch from AdminApp table (for employee)
        List<AdminApp> adminAppRecords = adminAppRepository.findAllByEmpAndYearAndAmount(
                receiverId, academicYearId, amount);
 
        // 4. Convert AdminApp records to AppSeriesDTO format
        List<AppSeriesDTO> adminAppSeriesList = new ArrayList<>();
        if (adminAppRecords != null && !adminAppRecords.isEmpty()) {
            for (AdminApp adminApp : adminAppRecords) {
                if (adminApp.getAppFromNo() != null && adminApp.getAppToNo() != null) {
                    String displaySeries = adminApp.getAppFromNo() + " - " + adminApp.getAppToNo();
                    int availableCount = adminApp.getTotalApp() != null ? adminApp.getTotalApp() : 0;
                   
                    AppSeriesDTO dto = new AppSeriesDTO(
                        displaySeries,
                        adminApp.getAppFromNo(),
                        adminApp.getAppToNo(),
                        availableCount
                    );
                   
                    // Set master info (same as start/end for AdminApp)
                    dto.setMasterStartNo(adminApp.getAppFromNo());
                    dto.setMasterEndNo(adminApp.getAppToNo());
                   
                    adminAppSeriesList.add(dto);
                }
            }
        }
       
        return adminAppSeriesList;
    }
}
   
     public Integer getDistributionIdBySeries(int receiverId, int start, int end, Double amount, boolean isPro) {
            if (isPro) {
                return distributionRepository.findIdByProAndRange(receiverId, start, end, amount)
                        .orElseThrow(() -> new RuntimeException("No Active Distribution found for this PRO range."));
            } else {
                return distributionRepository.findIdByEmpAndRange(receiverId, start, end, amount)
                        .orElseThrow(() -> new RuntimeException("No Active Distribution found for this Employee range."));
            }
        }
     
    // Generic helper to find the highest priority Role ID for ANY employee (Issuer or Receiver)
    private int getRoleTypeIdByEmpId(int empId) {
        List<UserAdminView> userRoles = userAdminViewRepository.findRolesByEmpId(empId);
       
        if (userRoles.isEmpty()) {
            // If the receiver has no role, they might be a basic employee.
            // You might want a default ID (e.g., 4) or throw an error.
            // For DGM Service, we expect them to be DGM (3).
            throw new RuntimeException("No valid roles found for Employee ID: " + empId);
        }
 
        int highestPriorityTypeId = Integer.MAX_VALUE;
        for (UserAdminView userView : userRoles) {
            String roleName = userView.getRole_name().trim().toUpperCase();
            int currentTypeId = switch (roleName) {
                case "ADMIN" -> 1;
                case "ZONAL ACCOUNTANT" -> 2;
                case "DGM" -> 3;
                // Add "PRO" or "AGENT" -> 4 if needed
                default -> -1;
            };
            if (currentTypeId != -1 && currentTypeId < highestPriorityTypeId) {
                highestPriorityTypeId = currentTypeId;
            }
        }
       
        if (highestPriorityTypeId == Integer.MAX_VALUE) {
             // Fallback or Error. If DGM service, maybe default to 3?
             return 3; // Defaulting to DGM if role logic is strict
        }
        return highestPriorityTypeId;
    }
   
    private int getIssuedTypeByUserId(int userId) {
        List<UserAdminView> userRoles = userAdminViewRepository.findRolesByEmpId(userId);
        if (userRoles.isEmpty()) throw new RuntimeException("No roles found for ID: " + userId);
 
        int highestPriorityTypeId = Integer.MAX_VALUE;
        for (UserAdminView userView : userRoles) {
            String roleName = userView.getRole_name().trim().toUpperCase();
            int currentTypeId = switch (roleName) {
                case "ADMIN" -> 1;
                case "ZONAL ACCOUNTANT" -> 2;
                case "DGM" -> 3;
                default -> -1;
            };
            if (currentTypeId != -1 && currentTypeId < highestPriorityTypeId) {
                highestPriorityTypeId = currentTypeId;
            }
        }
        return highestPriorityTypeId;
    }
 
    @Transactional
    public void submitForm(@NonNull FormSubmissionDTO formDto) {
        int issuerUserId = formDto.getUserId();
        int receiverEmpId = formDto.getDgmEmployeeId();
 
        // 1. AUTO-DETECT TYPES (Backend Logic)
        int issuedById = getRoleTypeIdByEmpId(issuerUserId);   // Who is sending?
        int issuedToId = getRoleTypeIdByEmpId(receiverEmpId);  // Who is receiving?
 
        // 2. Check Overlaps
        int startNo = Integer.parseInt(formDto.getApplicationNoFrom());
        int endNo = Integer.parseInt(formDto.getApplicationNoTo());
        List<Distribution> overlappingDists = distributionRepository.findOverlappingDistributions(
                formDto.getAcademicYearId(), startNo, endNo);
 
        if (!overlappingDists.isEmpty()) {
            handleOverlappingDistributions(overlappingDists, formDto);
        }
 
        // 3. Create & Map (Pass BOTH types now)
        Distribution distribution = new Distribution();
        mapDtoToDistribution(distribution, formDto, issuedById, issuedToId);
       
        distribution.setIssued_to_emp_id(receiverEmpId);
        distribution.setIssued_to_pro_id(null);
 
        // 4. Save & Flush
        Distribution savedDist = distributionRepository.saveAndFlush(distribution);
 
        // CRITICAL: Flush any pending remainders created in handleOverlappingDistributions
        // This ensures they are visible when rebuilding the sender's balance
        distributionRepository.flush();
 
        // 5. Recalculate Balances
        int stateId = savedDist.getState().getStateId();
        Float amount = formDto.getApplication_Amount();
 
        // A. Update Issuer (Sender) - CRITICAL: Always recalculate sender's balance
        System.out.println("DEBUG DGM: Recalculating sender balance for issuer: " + issuerUserId);
        recalculateBalanceForEmployee(issuerUserId, formDto.getAcademicYearId(), stateId, issuedById, issuerUserId, amount);
       
        // CRITICAL: Flush balance updates to ensure they're persisted
        balanceTrackRepository.flush();
       
        // B. Update Receiver (Pass the calculated issuedToId)
        addStockToReceiver(savedDist, formDto.getAcademicYearId(), issuedToId, issuerUserId, amount);
    }
   
    @Transactional
    public void updateForm(@NonNull Integer distributionId, @NonNull FormSubmissionDTO formDto) {
       
        // 1. Fetch Existing Record
        Distribution existingDistribution = distributionRepository.findById(distributionId)
                .orElseThrow(() -> new RuntimeException("Distribution record not found with ID: " + distributionId));
 
        // 2. Extract Immutable Data (Preserve Amount & State!)
        Float originalAmount = existingDistribution.getAmount();
        int issuerId = formDto.getUserId();
        int academicYearId = formDto.getAcademicYearId();
        int stateId = existingDistribution.getState().getStateId();
 
        // 3. Identify Changes
        int oldReceiverId = existingDistribution.getIssued_to_emp_id();
        int newReceiverId = formDto.getDgmEmployeeId();
        boolean isRecipientChanging = oldReceiverId != newReceiverId;
 
        int oldStart = (int) existingDistribution.getAppStartNo();
        int oldEnd = (int) existingDistribution.getAppEndNo();
        int newStart = Integer.parseInt(formDto.getApplicationNoFrom());
        int newEnd = Integer.parseInt(formDto.getApplicationNoTo());
        boolean isRangeChanging = oldStart != newStart || oldEnd != newEnd;
 
        // 4. AUTO-DETECT TYPES (Backend Logic)
        // We calculate these from the UserAdminView, we do NOT trust the frontend.
        int issuedById = getRoleTypeIdByEmpId(issuerId);
        int issuedToId = getRoleTypeIdByEmpId(newReceiverId); // <--- Calculated Here
 
        // -------------------------------------------------------
        // STEP 5: ARCHIVE OLD & SAVE NEW
        // -------------------------------------------------------
 
        // A. Inactivate Old (Flush to ensure DB sees it as inactive immediately)
        existingDistribution.setIsActive(0);
        distributionRepository.saveAndFlush(existingDistribution);
 
        // B. Create New Record
        Distribution newDist = new Distribution();
        // Map basic fields (Date, Zone, etc.)
        mapDtoToDistribution(newDist, formDto, issuedById, issuedToId);
       
        // OVERRIDE with correct Logic:
        newDist.setIssued_to_emp_id(newReceiverId); // DGM is always Employee
        newDist.setIssued_to_pro_id(null);
        newDist.setAmount(originalAmount); // CRITICAL: Preserve Original Amount
 
        distributionRepository.saveAndFlush(newDist); // Flush new record
 
        // -------------------------------------------------------
        // STEP 6: HANDLE REMAINDERS (If Range Shrank)
        // -------------------------------------------------------
        if (isRangeChanging) {
            // Leftover BEFORE the new range -> Stays with OLD Receiver
            if (oldStart < newStart) {
                createAndSaveRemainder(existingDistribution, oldStart, newStart - 1);
            }
            // Leftover AFTER the new range -> Stays with OLD Receiver
            if (oldEnd > newEnd) {
                createAndSaveRemainder(existingDistribution, newEnd + 1, oldEnd);
            }
        }
 
        // CRITICAL: Flush any pending remainders to ensure they are visible
        distributionRepository.flush();
 
        // -------------------------------------------------------
        // STEP 7: RECALCULATE BALANCES
        // -------------------------------------------------------
 
        // A. Update Issuer (Zone Officer or CO) - CRITICAL: Always recalculate sender's balance
        System.out.println("DEBUG DGM UPDATE: Recalculating sender balance for issuer: " + issuerId);
        recalculateBalanceForEmployee(issuerId, academicYearId, stateId, issuedById, issuerId, originalAmount);
       
        // CRITICAL: Flush balance updates to ensure they're persisted
        balanceTrackRepository.flush();
 
        // B. Update New Receiver (DGM)
        // We use the calculated 'issuedToId' variable here
        recalculateBalanceForEmployee(
            newReceiverId,
            academicYearId,
            stateId,
            issuedToId, // <--- FIX: Using local variable
            issuerId,
            originalAmount
        );
 
        // C. Update Old Receiver (If changed)
        if (isRecipientChanging) {
            // We must find the Type ID of the old receiver to call the method correctly
            // Use LIST check to avoid crashes
            java.util.List<BalanceTrack> oldBalances = balanceTrackRepository.findActiveBalancesByEmpAndAmount(academicYearId, oldReceiverId, originalAmount);
           
            if (!oldBalances.isEmpty()) {
                 BalanceTrack oldBalance = oldBalances.get(0);
                 int oldTypeId = oldBalance.getIssuedByType().getAppIssuedId();
                 
                 recalculateBalanceForEmployee(oldReceiverId, academicYearId, stateId, oldTypeId, issuerId, originalAmount);
            }
        }
    }
 
    // ---------------------------------------------------------
    // Smart Gap Detection Logic
    // ---------------------------------------------------------
    private void addStockToReceiver(Distribution savedDist, int academicYearId, int typeId, int createdBy, Float amount) {
        int newStart = savedDist.getAppStartNo();
        int newEnd = savedDist.getAppEndNo();
        int newCount = savedDist.getTotalAppCount();
        int targetEnd = newStart - 1;
        int receiverId = savedDist.getIssued_to_emp_id(); // DGM is Employee
 
        Optional<BalanceTrack> mergeableRow = balanceTrackRepository.findMergeableRowForEmployee(
                academicYearId, receiverId, amount, targetEnd);
 
        if (mergeableRow.isPresent()) {
            // MERGE
            BalanceTrack existing = mergeableRow.get();
            existing.setAppTo(newEnd);
            existing.setAppAvblCnt(existing.getAppAvblCnt() + newCount);
            balanceTrackRepository.save(existing);
        } else {
            // NEW ROW
            BalanceTrack newRow = createNewBalanceTrack(receiverId, academicYearId, typeId, createdBy);
            newRow.setAmount(amount);
            newRow.setAppFrom(newStart);
            newRow.setAppTo(newEnd);
            newRow.setAppAvblCnt(newCount);
            balanceTrackRepository.save(newRow);
        }
    }
 
    private BalanceTrack createNewBalanceTrack(int id, int acYear, int typeId, int createdBy, boolean isPro) {
        BalanceTrack nb = new BalanceTrack();
        nb.setAcademicYear(academicYearRepository.findById(acYear).orElseThrow());
        nb.setIssuedByType(appIssuedTypeRepository.findById(typeId).orElseThrow());
        nb.setIsActive(1);
        nb.setCreatedBy(createdBy);
 
        // Strict Validation logic
        if (isPro) {
            nb.setIssuedToProId(id);
            nb.setEmployee(null); // DB allows null now
        } else {
            nb.setEmployee(employeeRepository.findById(id).orElseThrow());
            nb.setIssuedToProId(null);
        }
        return nb;
    }
 
    private void createAndSaveRemainder(Distribution originalDist, int start, int end) {
        Distribution remainder = new Distribution();
        mapExistingToNewDistribution(remainder, originalDist);
        remainder.setIssued_to_emp_id(originalDist.getIssued_to_emp_id());
        remainder.setAppStartNo(start);
        remainder.setAppEndNo(end);
        remainder.setTotalAppCount((end - start) + 1);
        remainder.setIsActive(1);
        remainder.setAmount(originalDist.getAmount());
        distributionRepository.saveAndFlush(remainder);
    }
 
    // --- PRIVATE HELPER METHODS ---
 
    /**
     * Revised to use inactivation and insertion instead of update/delete.
     */
    private void handleOverlappingDistributions(List<Distribution> overlappingDists, FormSubmissionDTO request) {
        int newStart = Integer.parseInt(request.getApplicationNoFrom());
        int newEnd = Integer.parseInt(request.getApplicationNoTo());
 
        for (Distribution oldDist : overlappingDists) {
            int oldReceiverId = oldDist.getIssued_to_emp_id();
            if (oldReceiverId == request.getDgmEmployeeId()) continue;
 
            // Inactivate
            oldDist.setIsActive(0);
            distributionRepository.saveAndFlush(oldDist);
 
            int oldStart = oldDist.getAppStartNo();
            int oldEnd = oldDist.getAppEndNo();
           
            if (oldStart < newStart) createAndSaveRemainder(oldDist, oldStart, newStart - 1);
            if (oldEnd > newEnd) createAndSaveRemainder(oldDist, newEnd + 1, oldEnd);
           
            // CRITICAL: Flush remainders before recalculating balance
            distributionRepository.flush();
           
            // Recalculate Balance for Victim
            recalculateBalanceForEmployee(
                oldReceiverId,
                request.getAcademicYearId(),
                oldDist.getState().getStateId(),
                oldDist.getIssuedToType().getAppIssuedId(),
                request.getUserId(),
                oldDist.getAmount()
            );
           
            // CRITICAL: Flush balance updates
            balanceTrackRepository.flush();
        }
    }
 
    // 2. Recalculate Logic (Updated for Amount & AdminApp)
private void recalculateBalanceForEmployee(int employeeId, int academicYearId, int stateId, int typeId, int createdBy, Float amount) {
       
        // 1. CHECK: Is this a CO/Admin? (Check Master Table)
        Optional<AdminApp> adminApp = adminAppRepository.findByEmpAndYearAndAmount(
                employeeId, academicYearId, amount);
 
        if (adminApp.isPresent()) {
            // --- CASE A: CO / ADMIN (The Source) ---
            AdminApp master = adminApp.get();
           
            // FIX: Handle LIST return type
            List<BalanceTrack> balances = balanceTrackRepository.findActiveBalancesByEmpAndAmount(
                    academicYearId, employeeId, amount);
           
            BalanceTrack balance;
            if (balances.isEmpty()) {
                balance = createNewBalanceTrack(employeeId, academicYearId, typeId, createdBy);
                balance.setAmount(amount);
            } else {
                // Admins act as a single bucket, so we pick the first one
                balance = balances.get(0);
            }
 
            int totalDistributed = distributionRepository.sumTotalAppCountByCreatedByAndAmount(
                    employeeId, academicYearId, amount).orElse(0);
 
            // CRITICAL FIX: Calculate app_from based on what they've distributed
            // If they've distributed from the beginning, app_from should be the next available number
            // Otherwise, use the master start
            Optional<Integer> maxDistributedEnd = distributionRepository
                    .findMaxAppEndNoByCreatedByAndAmount(employeeId, academicYearId, amount);
           
            int calculatedAppFrom;
            if (maxDistributedEnd.isPresent() && maxDistributedEnd.get() >= master.getAppFromNo()) {
                // They've distributed from the beginning - app_from should be next available
                calculatedAppFrom = maxDistributedEnd.get() + 1;
                System.out.println("DEBUG DGM: Admin/CO distributed up to " + maxDistributedEnd.get() +
                        ", setting app_from to " + calculatedAppFrom);
            } else {
                // They haven't distributed from the beginning yet - use master start
                calculatedAppFrom = master.getAppFromNo();
                System.out.println("DEBUG DGM: Admin/CO hasn't distributed from beginning, using master start: " + calculatedAppFrom);
            }
           
            // Ensure app_from doesn't exceed master end
            if (calculatedAppFrom > master.getAppToNo()) {
                calculatedAppFrom = master.getAppToNo() + 1; // All apps distributed
            }
           
            balance.setAppFrom(calculatedAppFrom); // Use calculated start (next available or master start)
            balance.setAppTo(master.getAppToNo());
            balance.setAppAvblCnt(master.getTotalApp() - totalDistributed);
           
            balanceTrackRepository.saveAndFlush(balance);
       
        } else {
            // --- CASE B: INTERMEDIARIES (Zone/DGM) ---
            // Rebuilds rows to match gaps exactly
            rebuildBalancesFromDistributions(
                    employeeId,
                    academicYearId,
                    typeId,
                    createdBy,
                    amount // <--- Ensure you convert Float to Double here
                );
        }
    }
 
//--- HELPER: Rebuild Balance Rows (Preserves Gaps) ---
// This deletes old balance rows and creates new ones based on what is currently held in Distribution table
private void rebuildBalancesFromDistributions(int empId, int acYearId, int typeId, int createdBy, Float amount) {
   
    // 1. Get ALL Active Distributions currently HELD by this user
    List<Distribution> holdings = distributionRepository.findActiveByIssuedToEmpIdAndAmountOrderByStart(
            empId, acYearId, amount);
 
    // 2. Get CURRENT Active Balance Rows for this amount
    List<BalanceTrack> currentBalances = balanceTrackRepository.findActiveBalancesByEmpAndAmount(
            acYearId, empId, amount);
 
    // 3. STRATEGY: Soft Delete OLD rows, Insert NEW rows
   
    // CRITICAL: Flush to ensure latest distribution data is visible
    distributionRepository.flush();
   
    // A. Mark old rows as inactive (Clear the slate)
    for (BalanceTrack b : currentBalances) {
        b.setIsActive(0);
        balanceTrackRepository.saveAndFlush(b);
    }
   
    // B. Create new rows for every active distribution held (Mirroring reality)
    for (Distribution dist : holdings) {
        BalanceTrack nb = createNewBalanceTrack(empId, acYearId, typeId, createdBy);
       
        nb.setAmount(amount);
        nb.setAppFrom((int) dist.getAppStartNo());
        nb.setAppTo((int) dist.getAppEndNo());
        nb.setAppAvblCnt(dist.getTotalAppCount());
       
        balanceTrackRepository.saveAndFlush(nb);
    }
   
    // CRITICAL: Flush all balance updates to ensure they're persisted
    balanceTrackRepository.flush();
    System.out.println("DEBUG DGM: Rebuilt " + holdings.size() + " balance rows for employee " + empId + " with amount " + amount);
}
    /**
     * Helper to create a new active Distribution record based on an existing one
     * but setting the new IssuedToEmpId (the old receiver) and setting IsActive=1.
     */
    private Distribution createRemainderDistribution(Distribution originalDist, int receiverId) {
        Distribution remainderDistribution = new Distribution();
        // Copy most fields from the original distribution
        mapExistingToNewDistribution(remainderDistribution, originalDist);
 
        // Set specific fields for the remainder
        remainderDistribution.setIssued_to_emp_id(receiverId); // Stays with the OLD receiver
        remainderDistribution.setIsActive(1);
 
        // Note: The range and count will be set by the caller
        return remainderDistribution;
    }
 
    // FIX: Added 4th parameter 'int issuedToId'
    private void mapDtoToDistribution(Distribution distribution, FormSubmissionDTO formDto, int issuedById, int issuedToId) {
       
        int appNoFrom = Integer.parseInt(formDto.getApplicationNoFrom());
        int appNoTo = Integer.parseInt(formDto.getApplicationNoTo());
 
        // Map Basic Fields
        academicYearRepository.findById(formDto.getAcademicYearId()).ifPresent(distribution::setAcademicYear);
        zoneRepository.findById(formDto.getZoneId()).ifPresent(distribution::setZone);
        campusRepository.findById(formDto.getCampusId()).ifPresent(distribution::setCampus);
       
        cityRepository.findById(formDto.getCityId()).ifPresent(city -> {
            distribution.setCity(city);
            if (city.getDistrict() != null) {
                distribution.setDistrict(city.getDistrict());
                if (city.getDistrict().getState() != null) {
                    distribution.setState(city.getDistrict().getState());
                }
            }
        });
 
        // --- FIX: Use the passed arguments for Types ---
        appIssuedTypeRepository.findById(issuedById).ifPresent(distribution::setIssuedByType);
        appIssuedTypeRepository.findById(issuedToId).ifPresent(distribution::setIssuedToType);
        // ----------------------------------------------
 
        distribution.setAppStartNo(appNoFrom);
        distribution.setAppEndNo(appNoTo);
        distribution.setTotalAppCount(formDto.getRange());
        distribution.setAmount(formDto.getApplication_Amount());
       
        // Fix for Date (Always use Now to prevent nulls)
        distribution.setIssueDate(java.time.LocalDateTime.now());
       
        distribution.setIsActive(1);
        distribution.setCreated_by(formDto.getUserId());
       
        // Note: We set issued_to_emp_id in the main method, not here.
    }
 
    private void mapExistingToNewDistribution(Distribution newDist, Distribution oldDist) {
           // ... (Copy standard fields) ...
           newDist.setAcademicYear(oldDist.getAcademicYear());
           newDist.setState(oldDist.getState());
           newDist.setDistrict(oldDist.getDistrict());
           newDist.setCity(oldDist.getCity());
           newDist.setZone(oldDist.getZone());
           newDist.setCampus(oldDist.getCampus());
           newDist.setIssuedByType(oldDist.getIssuedByType());
           newDist.setIssuedToType(oldDist.getIssuedToType());
           newDist.setIssued_to_emp_id(oldDist.getIssued_to_emp_id());
           newDist.setAppStartNo(oldDist.getAppStartNo());
           newDist.setAppEndNo(oldDist.getAppEndNo());
           newDist.setTotalAppCount(oldDist.getTotalAppCount());
           newDist.setIssueDate(oldDist.getIssueDate());
           newDist.setIsActive(1);
           newDist.setCreated_by(oldDist.getCreated_by());
        }
 
    private BalanceTrack createNewBalanceTrack(int employeeId, int academicYearId, int typeId, int createdBy) {
        BalanceTrack nb = new BalanceTrack();
        nb.setEmployee(employeeRepository.findById(employeeId).orElseThrow());
        nb.setAcademicYear(academicYearRepository.findById(academicYearId).orElseThrow());
        nb.setIssuedByType(appIssuedTypeRepository.findById(typeId).orElseThrow());
        nb.setAppAvblCnt(0);
        nb.setIsActive(1);
        nb.setCreatedBy(createdBy);
        nb.setIssuedToProId(null); // Strict Validation for Employee
        return nb;
    }
 
}