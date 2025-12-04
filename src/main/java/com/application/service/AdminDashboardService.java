package com.application.service;
 
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
 
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
 
import java.util.Optional;
 
import com.application.dto.DashboardResponseDTO;
import com.application.dto.GraphBarDTO;
import com.application.dto.GraphResponseDTO;
import com.application.dto.MetricCardDTO;
import com.application.dto.MetricsAggregateDTO;
import com.application.entity.AcademicYear;
import com.application.repository.AdminAppRepository;
import com.application.repository.AcademicYearRepository;
import com.application.repository.AppStatusTrackRepository;
import com.application.repository.BalanceTrackRepository;
import com.application.repository.UserAppSoldRepository;
 
@Service
public class AdminDashboardService {
 
    @Autowired
    private AdminAppRepository adminAppRepository;
    
    @Autowired
    private AppStatusTrackRepository appStatusTrackRepository;
    
    @Autowired
    private BalanceTrackRepository balanceTrackRepository;
    
    @Autowired
    private UserAppSoldRepository userAppSoldRepository;
    
    @Autowired
    private AcademicYearRepository academicYearRepository;
 
    public DashboardResponseDTO getDashboardData(Integer employeeId) {
        
        // Get current year (latest year) from AppStatusTrackRepository, same as AppStatusTrackService
        Integer currentYearId = appStatusTrackRepository.findLatestYearId();
        // If no year found, return zeros for all data
        if (currentYearId == null) {
            return createEmptyDashboardResponse();
        }
        Integer previousYearId = currentYearId - 1;
        
        // Sum total_app from AdminApp table for given employee and current academic year
        Long currentYearTotal = adminAppRepository.sumTotalAppByEmployeeAndAcademicYear(employeeId, currentYearId);
        int currTotalApplications = currentYearTotal != null ? currentYearTotal.intValue() : 0;
        
        // Sum total_app from AdminApp table for given employee and previous academic year
        Long previousYearTotal = adminAppRepository.sumTotalAppByEmployeeAndAcademicYear(employeeId, previousYearId);
        int prevTotalApplications = previousYearTotal != null ? previousYearTotal.intValue() : 0;
        
        // Calculate percentage change (same logic as AppStatusTrackService)
        int percentageChange = clampChange(prevTotalApplications, currTotalApplications);
        
        // Get metrics data from AppStatusTrack for current year
        Optional<MetricsAggregateDTO> currentYearMetrics = appStatusTrackRepository.getMetricsByYear(currentYearId);
        long currSold = 0L;
        long currConfirmed = 0L;
        long currDamaged = 0L;
        long currUnavailable = 0L;
        if (currentYearMetrics.isPresent()) {
            MetricsAggregateDTO metrics = currentYearMetrics.get();
            currSold = metrics.appSold();
            currConfirmed = metrics.appConfirmed();
            currDamaged = metrics.appDamaged();
            currUnavailable = metrics.appUnavailable();
        }
        
        // Get metrics data from AppStatusTrack for previous year
        Optional<MetricsAggregateDTO> previousYearMetrics = appStatusTrackRepository.getMetricsByYear(previousYearId);
        long prevSold = 0L;
        long prevConfirmed = 0L;
        long prevDamaged = 0L;
        long prevUnavailable = 0L;
        if (previousYearMetrics.isPresent()) {
            MetricsAggregateDTO metrics = previousYearMetrics.get();
            prevSold = metrics.appSold();
            prevConfirmed = metrics.appConfirmed();
            prevDamaged = metrics.appDamaged();
            prevUnavailable = metrics.appUnavailable();
        }
        
        // Get available data from BalanceTrack for current year
        Long currentYearAvailable = balanceTrackRepository.sumAppAvblCntByEmployeeAndAcademicYear(employeeId, currentYearId);
        int currAvailable = currentYearAvailable != null ? currentYearAvailable.intValue() : 0;
        
        // Get available data from BalanceTrack for previous year
        Long previousYearAvailable = balanceTrackRepository.sumAppAvblCntByEmployeeAndAcademicYear(employeeId, previousYearId);
        int prevAvailable = previousYearAvailable != null ? previousYearAvailable.intValue() : 0;
        
        // Calculate Issued = Total App (AdminApp) - Available (BalanceTrack)
        int currIssued = currTotalApplications - currAvailable;
        int prevIssued = prevTotalApplications - prevAvailable;
        
        // Get With PRO data from AppStatusTrack (issuedByType.appIssuedId = 4) for current year
        Long currentYearWithPro = appStatusTrackRepository.getWithProAvailableByYear(currentYearId);
        int currWithPro = currentYearWithPro != null ? currentYearWithPro.intValue() : 0;
        
        // Get With PRO data from AppStatusTrack for previous year
        Long previousYearWithPro = appStatusTrackRepository.getWithProAvailableByYear(previousYearId);
        int prevWithPro = previousYearWithPro != null ? previousYearWithPro.intValue() : 0;
        
        // Calculate percentage changes
        int soldPercentageChange = clampChange((int) prevSold, (int) currSold);
        int confirmedPercentageChange = clampChange((int) prevConfirmed, (int) currConfirmed);
        int damagedPercentageChange = clampChange((int) prevDamaged, (int) currDamaged);
        int unavailablePercentageChange = clampChange((int) prevUnavailable, (int) currUnavailable);
        int availablePercentageChange = clampChange(prevAvailable, currAvailable);
        int issuedPercentageChange = clampChange(prevIssued, currIssued);
        int withProPercentageChange = clampChange(prevWithPro, currWithPro);
 
        // Create metric cards
        List<MetricCardDTO> metricCards = new ArrayList<>();
        metricCards.add(new MetricCardDTO("Total Applications", currTotalApplications, percentageChange, "total_applications"));
        metricCards.add(new MetricCardDTO("Sold", (int) currSold, soldPercentageChange, "sold"));
        metricCards.add(new MetricCardDTO("Confirmed", (int) currConfirmed, confirmedPercentageChange, "confirmed"));
        metricCards.add(new MetricCardDTO("Available", currAvailable, availablePercentageChange, "available"));
        metricCards.add(new MetricCardDTO("Issued", currIssued, issuedPercentageChange, "issued"));
        metricCards.add(new MetricCardDTO("Damaged", (int) currDamaged, damagedPercentageChange, "damaged"));
        metricCards.add(new MetricCardDTO("Unavailable", (int) currUnavailable, unavailablePercentageChange, "unavailable"));
        metricCards.add(new MetricCardDTO("With PRO", currWithPro, withProPercentageChange, "with_pro"));
 
        // Generate graph data for previous 4 years (current year + 3 previous years)
        GraphResponseDTO graphData = generateGraphData(employeeId, currentYearId);
 
        // Create response
        DashboardResponseDTO response = new DashboardResponseDTO();
        response.setMetricCards(metricCards);
        response.setGraphData(graphData);
 
        return response;
    }
    
    // Helper method to create empty dashboard response with all zeros
    private DashboardResponseDTO createEmptyDashboardResponse() {
        List<MetricCardDTO> metricCards = new ArrayList<>();
        metricCards.add(new MetricCardDTO("Total Applications", 0, 0, "total_applications"));
        metricCards.add(new MetricCardDTO("Sold", 0, 0, "sold"));
        metricCards.add(new MetricCardDTO("Confirmed", 0, 0, "confirmed"));
        metricCards.add(new MetricCardDTO("Available", 0, 0, "available"));
        metricCards.add(new MetricCardDTO("Issued", 0, 0, "issued"));
        metricCards.add(new MetricCardDTO("Damaged", 0, 0, "damaged"));
        metricCards.add(new MetricCardDTO("Unavailable", 0, 0, "unavailable"));
        metricCards.add(new MetricCardDTO("With PRO", 0, 0, "with_pro"));
        
        // Create empty graph data for 4 years with zeros
        List<GraphBarDTO> barList = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            GraphBarDTO dto = new GraphBarDTO();
            dto.setYear("Year " + (2025 - i));
            dto.setIssuedPercent(0);
            dto.setSoldPercent(0);
            dto.setIssuedCount(0);
            dto.setSoldCount(0);
            barList.add(dto);
        }
        
        GraphResponseDTO graphData = new GraphResponseDTO();
        graphData.setGraphBarData(barList);
        
        DashboardResponseDTO response = new DashboardResponseDTO();
        response.setMetricCards(metricCards);
        response.setGraphData(graphData);
        
        return response;
    }
    
    // Helper methods for percentage calculation (same as AppStatusTrackService)
    private int clampChange(int prev, int curr) {
        if (prev == 0) {
            // If previous was 0, any increase is considered 100% growth, but if current is also 0, return 0
            return curr > 0 ? 100 : 0;
        }
        double raw = ((double) (curr - prev) / prev) * 100;
        return clamp(raw);
    }
 
    private int clamp(double value) {
        if (value > 100) return 100;
        if (value < -100) return -100;
        return (int) Math.round(value);
    }
    
    private GraphResponseDTO generateGraphData(Integer employeeId, Integer currentYearId) {
        // Handle null currentYearId
        if (currentYearId == null) {
            currentYearId = 0; // Default to 0 if null
        }
        
        // Get previous 4 years (current year + 3 previous years)
        List<Integer> yearIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            yearIds.add(currentYearId - i);
        }
        
            // Get year-wise data from UserAppSold - NO employee filter, only current year + isActive = 1
        List<Object[]> allRows = userAppSoldRepository.getYearWiseIssuedAndSold();
        
        // Create a map of yearId -> [issued, sold] for quick lookup
        // Filter to only include current year data
        Map<Integer, long[]> yearDataMap = new HashMap<>();
        if (allRows != null) {
            for (Object[] row : allRows) {
                if (row != null && row.length >= 3) {
                    Integer yearId = row[0] != null ? (Integer) row[0] : null;
                    Long issued = row[1] != null ? ((Number) row[1]).longValue() : 0L;
                    Long sold = row[2] != null ? ((Number) row[2]).longValue() : 0L;
                    // Only include current year data
                    if (yearId != null && yearId.equals(currentYearId)) {
                        yearDataMap.put(yearId, new long[]{issued, sold});
                    }
                }
            }
        }
        
        // Get AcademicYear entities for year labels
        List<AcademicYear> academicYears = academicYearRepository.findByAcdcYearIdIn(yearIds);
        Map<Integer, AcademicYear> yearMap = new HashMap<>();
        if (academicYears != null) {
            yearMap = academicYears.stream()
                .collect(Collectors.toMap(AcademicYear::getAcdcYearId, y -> y));
        }
        
        // Build graph bar data for all 4 years - always return 4 years with zeros if no data
        List<GraphBarDTO> barList = new ArrayList<>();
        for (Integer yearId : yearIds) {
            long[] data = yearDataMap.getOrDefault(yearId, new long[]{0L, 0L});
            long issued = data[0];
            long sold = data[1];
            
            AcademicYear year = yearMap.get(yearId);
            String yearLabel = year != null ? year.getAcademicYear() : "Year " + yearId;
            
            // Calculate percentages - default to 0 if no data
            int issuedPercent = 0;
            int soldPercent = 0;
            if (issued > 0) {
                issuedPercent = 100; // 100% as baseline when data exists
                soldPercent = (int) Math.round((sold * 100.0) / issued);
            }
            
            GraphBarDTO dto = new GraphBarDTO();
            dto.setYear(yearLabel);
            dto.setIssuedPercent(issuedPercent);
            dto.setSoldPercent(soldPercent);
            dto.setIssuedCount((int) issued);
            dto.setSoldCount((int) sold);
            
            barList.add(dto);
        }
        
        GraphResponseDTO response = new GraphResponseDTO();
        response.setGraphBarData(barList);
        
        return response;
    }
}
 