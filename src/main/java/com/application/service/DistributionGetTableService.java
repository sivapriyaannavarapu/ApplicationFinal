package com.application.service;
 
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
 
import com.application.dto.DistributionGetTableDTO;
import com.application.entity.AdminApp;
import com.application.entity.Distribution;
import com.application.repository.AdminAppRepository;
import com.application.repository.CampaignRepository;
import com.application.repository.DgmRepository;
import com.application.repository.DistributionRepository;
import com.application.repository.StateRepository;
import com.application.repository.DistrictRepository;
import com.application.repository.CityRepository;
import com.application.repository.EmployeeRepository; // 🎯 New Import
 
@Service
public class DistributionGetTableService {
 
    @Autowired
    private DistributionRepository distributionRepository;
 
    @Autowired
    private DgmRepository dgmRepository;
 
    @Autowired
    private CampaignRepository campaignRepository;
    
    @Autowired
    private AdminAppRepository adminAppRepository;
    
    @Autowired
    StateRepository state;
    
    @Autowired
    DistrictRepository district;
    
    @Autowired
    CityRepository city;
    
    @Autowired
    private EmployeeRepository employeeRepository;
    
//    @Cacheable(value = "distributionsByEmployee", key = "#empId")
    public List<DistributionGetTableDTO> getDistributionsByEmployeeAndIssuedToType(int empId, int issuedToTypeId) {
        
        final int PRO_ISSUED_TYPE_ID = 4;
        
        List<Distribution> distributions =
                distributionRepository.findByCreatedByAndIssuedToType(empId, issuedToTypeId);
       
        return distributions.stream().map(d -> {
            DistributionGetTableDTO dto = new DistributionGetTableDTO();
 
            // 1. Set displaySeries
            dto.setDisplaySeries(d.getAppStartNo() + " - " + d.getAppEndNo());
 
            // 2. Populate all base fields and fetch mobile number
            dto.setAppDistributionId(d.getAppDistributionId());
            dto.setAppStartNo(d.getAppStartNo());
            dto.setAppEndNo(d.getAppEndNo());
            dto.setTotalAppCount(d.getTotalAppCount());
            dto.setAmount(d.getAmount());
            dto.setIsActive(d.getIsActive());
            dto.setCreated_by(d.getCreated_by());
            dto.setIssued_to_emp_id(d.getIssued_to_emp_id());
            dto.setIssueDate(d.getIssueDate());
            
            // 🎯 NEW LOGIC: Fetch Mobile Number
            try {
                // The repository returns a String, but the DTO field is Long (mobileNmuber)
                String mobileString = employeeRepository.findMobileNoByEmpId(d.getCreated_by());
                if (mobileString != null) {
                     // Parse the String to Long for the DTO (handle typo: mobileNmuber)
                    dto.setMobileNmuber(Long.parseLong(mobileString));
                }
            } catch (Exception e) {
                // Handle case where mobile number might be invalid or not found
                System.err.println("Error fetching or parsing mobile number for employee " + d.getCreated_by() + ": " + e.getMessage());
            }
 
            // 3. Handle related IDs and NAMES from JPA relationships
            int acdcYearId = 0;
            
            if (d.getIssuedByType() != null) dto.setIssued_by_type_id(d.getIssuedByType().getAppIssuedId());
            if (d.getIssuedToType() != null) dto.setIssued_to_type_id(d.getIssuedToType().getAppIssuedId());
            
            // --- LOCATION IDs and NAMES (FIXED) ---
            if (d.getState() != null) {
                dto.setState_id(d.getState().getStateId());
                dto.setStatename(d.getState().getStateName());
            }
            if (d.getDistrict() != null) {
                dto.setDistrict_id(d.getDistrict().getDistrictId());
                dto.setDistrictname(d.getDistrict().getDistrictName());
            }
            if (d.getCity() != null) {
                dto.setCity_id(d.getCity().getCityId());
                dto.setCityname(d.getCity().getCityName());
            }
            
            if (d.getZone() != null) dto.setZone_id(d.getZone().getZoneId());
            if (d.getCampus() != null) dto.setCmps_id(d.getCampus().getCampusId());
            if (d.getAcademicYear() != null) {
                acdcYearId = d.getAcademicYear().getAcdcYearId();
                dto.setAcdc_year_id(acdcYearId);
            }
            
            // 4. Master Range Enrichment
            int masterStart = 0;
            int masterEnd = 0;
            Double amount = d.getAmount() != null ? d.getAmount().doubleValue() : null;
 
            if (acdcYearId > 0 && amount != null) {
                List<AdminApp> masterRecords = adminAppRepository.findMasterRecordByYearAndAmount(
                        acdcYearId, amount);
 
                if (!masterRecords.isEmpty()) {
                    AdminApp master = masterRecords.get(0);
                    masterStart = master.getAppFromNo();
                    masterEnd = master.getAppToNo();
                }
            }
            
            // 5. Set Master Range (Uncomment if fields exist in DTO)
            // dto.setMasterStartNo(masterStart);
            // dto.setMasterEndNo(masterEnd);
            
            
            // 6. Derive the additional fields (Names, Zone, DGM, Campaign)
            String issuedToName = null;
            if (d.getIssuedToEmployee() != null) {
                issuedToName = d.getIssuedToEmployee().getFirst_name() + " " + d.getIssuedToEmployee().getLast_name();
            } else if (d.getCampus() != null) {
                issuedToName = d.getCampus().getCampusName();
            } else if (d.getZone() != null) {
                issuedToName = d.getZone().getZoneName();
            } else if (d.getDistrict() != null) {
                issuedToName = d.getDistrict().getDistrictName();
            } else if (d.getCity() != null) {
                issuedToName = d.getCity().getCityName();
            } else if (d.getState() != null) {
                issuedToName = d.getState().getStateName();
            }
            dto.setIssuedToName(issuedToName);
 
            String zoneName = null;
            if (d.getZone() != null) {
                zoneName = d.getZone().getZoneName();
            }
            dto.setZoneName(zoneName);
 
            String campusName = null;
            if (d.getCampus() != null) {
                campusName = d.getCampus().getCampusName();
            }
            dto.setCampusName(campusName);
 
            String dgmName = null;
            // ... (DGM repository logic here) ...
            dto.setDgmName(dgmName);
 
            String campaignAreaName = null;
            int campaignAreaId = 0;
            // ... (Campaign repository logic here) ...
            
            dto.setCampaignAreaName(campaignAreaName);
            dto.setCampaignAreaId(campaignAreaId);
            
            return dto;
        }).toList();
    }
}