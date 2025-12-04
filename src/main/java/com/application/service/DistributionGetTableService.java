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
    
//    @Cacheable(value = "distributionsByEmployee", key = "#empId")
    public List<DistributionGetTableDTO> getDistributionsByEmployeeAndIssuedToType(int empId, int issuedToTypeId) {
        
        // Assume ISSUED_TO_PRO_TYPE_ID is a defined constant (e.g., 4)
        final int PRO_ISSUED_TYPE_ID = 4;
        
        List<Distribution> distributions =
                distributionRepository.findByCreatedByAndIssuedToType(empId, issuedToTypeId);
 
        return distributions.stream().map(d -> {
            DistributionGetTableDTO dto = new DistributionGetTableDTO();
 
            // 1. Set displaySeries (Simple Concatenation)
            // This achieves the same result as the JPQL CONCAT in the BalanceTrackRepository
            dto.setDisplaySeries(d.getAppStartNo() + " - " + d.getAppEndNo());
 
            // 2. Populate all fields from the Distribution entity
            dto.setAppDistributionId(d.getAppDistributionId());
            dto.setAppStartNo(d.getAppStartNo());
            dto.setAppEndNo(d.getAppEndNo());
            dto.setTotalAppCount(d.getTotalAppCount());
            dto.setAmount(d.getAmount());
            dto.setIsActive(d.getIsActive());
            dto.setCreated_by(d.getCreated_by());
            dto.setIssued_to_emp_id(d.getIssued_to_emp_id());
            dto.setIssueDate(d.getIssueDate());
            
            // 3. Handle related IDs from relationships
            int acdcYearId = 0;
            
            if (d.getIssuedByType() != null) dto.setIssued_by_type_id(d.getIssuedByType().getAppIssuedId());
            if (d.getIssuedToType() != null) dto.setIssued_to_type_id(d.getIssuedToType().getAppIssuedId());
            if (d.getCity() != null) dto.setCity_id(d.getCity().getCityId());
            if (d.getState() != null) dto.setState_id(d.getState().getStateId());
            if (d.getZone() != null) dto.setZone_id(d.getZone().getZoneId());
            if (d.getDistrict() != null) dto.setDistrict_id(d.getDistrict().getDistrictId());
            if (d.getCampus() != null) dto.setCmps_id(d.getCampus().getCampusId());
            if (d.getAcademicYear() != null) {
                acdcYearId = d.getAcademicYear().getAcdcYearId(); // Store for Master fetch
                dto.setAcdc_year_id(acdcYearId);
            }
            
            // 4. Master Range Enrichment (Equivalent to logic in getActiveSeriesForReceiver)
            int masterStart = 0;
            int masterEnd = 0;
            Double amount = d.getAmount() != null ? d.getAmount().doubleValue() : null;
 
            if (acdcYearId > 0 && amount != null) {
                // FIX: Pass 'amount' directly (it is Double).
                List<AdminApp> masterRecords = adminAppRepository.findMasterRecordByYearAndAmount(
                        acdcYearId, amount);
 
                if (!masterRecords.isEmpty()) {
                    AdminApp master = masterRecords.get(0); // Take the first active record
                    masterStart = master.getAppFromNo();
                    masterEnd = master.getAppToNo();
                }
            }
            
            // 5. Set Master Range (These fields MUST be in DistributionGetTableDTO)
            // dto.setMasterStartNo(masterStart); // Uncomment if added to DTO
            // dto.setMasterEndNo(masterEnd);     // Uncomment if added to DTO
            
            
            // 6. Derive the additional fields (Name, Zone, DGM, Campaign)
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
            // Find DGM name based on zone or campus ID from the distribution record
            if (d.getZone() != null) {
                // List<Dgm> dgms = dgmRepository.findByZoneId(d.getZone().getZoneId());
                // if (!dgms.isEmpty() && dgms.get(0).getEmployee() != null) {
                //     Dgm dgm = dgms.get(0);
                //     dgmName = dgm.getEmployee().getFirst_name() + " " + dgm.getEmployee().getLast_name();
                // }
            } else if (d.getCampus() != null) {
                // List<Dgm> dgms = dgmRepository.findByCampusId(d.getCampus().getCampusId());
                // if (!dgms.isEmpty() && dgms.get(0).getEmployee() != null) {
                //     Dgm dgm = dgms.get(0);
                //     dgmName = dgm.getEmployee().getFirst_name() + " " + dgm.getEmployee().getLast_name();
                // }
            }
            dto.setDgmName(dgmName);
            
            // Add logic for campaign area name here
            String campaignAreaName = null;
            int campaignAreaId = 0;
            
            if (d.getCampus() != null) {
                // Campaign campaign = campaignRepository.findByCampus_CampusId(d.getCampus().getCampusId());
                // if (campaign != null) {
                //     campaignAreaName = campaign.getAreaName();
                //     campaignAreaId = campaign.getCampaignId();
                // }
            }
            dto.setCampaignAreaName(campaignAreaName);
            dto.setCampaignAreaId(campaignAreaId);
            
            return dto;
        }).toList();
    }
}