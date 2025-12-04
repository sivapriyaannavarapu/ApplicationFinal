package com.application.service;
 
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.application.dto.AppStatusDTO;
import com.application.entity.AppStatusTrackView;
import com.application.entity.Campus;
import com.application.exception.ZoneIdRequiredException;
import com.application.repository.AppStatusTrackViewRepository;
import com.application.repository.CampusRepository;
 
@Service
public class ApplicationStatusViewService {
 
    @Autowired
    private AppStatusTrackViewRepository appStatusTrackViewRepository;
    @Autowired private CampusRepository campusRepository;

//    @Cacheable(value = "appStatusByCampus", key = "#cmpsId")
    public List<AppStatusTrackView> getApplicationStatusByCampus(int cmpsId) {
        return appStatusTrackViewRepository.findByCmps_id(cmpsId);
    }
    
    public List<AppStatusTrackView> getApplicationStatusByEmployeeCampus(int empId) {
        try {
            List<AppStatusTrackView> result = appStatusTrackViewRepository.findByEmployeeCampus(empId);
            return result;
        } catch (Exception e) {
            throw e;
        }
    }
    
    @Cacheable(value = "allstatustable")
    public List<AppStatusDTO> getAllStatus() {
        return appStatusTrackViewRepository.getAllStatusData();
    }
    
    public List<AppStatusDTO> fetchApplicationStatus(String category, Integer zoneId) {
    	 
        List<Campus> campuses;
 
        // --------------------------------------------
        // CATEGORY = SCHOOL  → businessId = 2
        // zoneId is mandatory for SCHOOL
        // --------------------------------------------
        if (category.equalsIgnoreCase("school")) {
            int businessId = 2;
 
            if (zoneId == null) {
                throw new ZoneIdRequiredException("Zone ID must be provided for SCHOOL category");
            }
 
            campuses = campusRepository.findSchoolCampusesByZone(businessId, zoneId);
        }
 
        // --------------------------------------------
        // CATEGORY = COLLEGE  → businessId = 1
        // zoneId is optional
        // --------------------------------------------
        else if (category.equalsIgnoreCase("college")) {
            int businessId = 1;
 
            if (zoneId != null) {
                campuses = campusRepository.findCollegeCampusesByZone(businessId, zoneId);
            } else {
                campuses = campusRepository.findCollegeCampuses(businessId);
            }
        }
 
        // --------------------------------------------
        // INVALID CATEGORY
        // --------------------------------------------
        else {
            return List.of();
        }
 
        // Extract campus IDs
        List<Integer> campusIds = campuses.stream()
                .map(Campus::getCampusId)
                .toList();
 
        if (campusIds.isEmpty()) {
            return List.of();
        }
 
        return appStatusTrackViewRepository.findDTOByCampusIds(campusIds);
    }
 
 
}