package com.application.service;
 
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.application.dto.CombinedAnalyticsDTO;
import com.application.dto.GraphBarDTO;
import com.application.dto.GraphDTO;
import com.application.dto.GraphSoldSummaryDTO;
import com.application.dto.MetricDTO;
import com.application.dto.MetricsAggregateDTO;
import com.application.dto.MetricsDataDTO;
import com.application.dto.YearlyGraphPointDTO;
import com.application.entity.AcademicYear;
import com.application.entity.Dgm;
import com.application.entity.SCEmployeeEntity;
import com.application.entity.ZonalAccountant;
import com.application.repository.AcademicYearRepository;
import com.application.repository.AppStatusTrackRepository;
import com.application.repository.DgmRepository;
import com.application.repository.SCEmployeeRepository;
import com.application.repository.UserAppSoldRepository;
import com.application.repository.ZonalAccountantRepository;
 
@Service
public class ApplicationAnalyticsService {
 
    @Autowired
    private UserAppSoldRepository userAppSoldRepository;
 
    @Autowired
    private AppStatusTrackRepository appStatusTrackRepository;
 
    @Autowired
    private AcademicYearRepository academicYearRepository;
   
    @Autowired
    private SCEmployeeRepository scEmployeeRepository;
    
    @Autowired
    private ZonalAccountantRepository zonalAccountantRepository;
   
    @Autowired
    private DgmRepository dgmRepository;
 
public CombinedAnalyticsDTO getRollupAnalytics(Integer empId) {
        
        // 1. Get Basic Employee Details
        List<SCEmployeeEntity> employeeList = scEmployeeRepository.findByEmpId(empId);
        if (employeeList.isEmpty()) {
            return createEmptyAnalytics("Invalid Employee", empId, "Employee not found", "N/A");
        }
        
        SCEmployeeEntity employee = employeeList.get(0);
        String role = employee.getEmpStudApplicationRole();
        String designation = employee.getDesignationName();
        
        if (role == null) return createEmptyAnalytics("Null Role", empId, "No Role", designation);

        // 2. Route based on Role
        String trimmedRole = role.trim();

        if (trimmedRole.equalsIgnoreCase("DGM")) {
            return getDgmDirectAnalytics(employee); // NEW METHOD
        } 
        else if (trimmedRole.equalsIgnoreCase("ZONAL ACCOUNTANT")) {
            return getZonalDirectAnalytics(employee); // NEW METHOD
        } 
        else {
            return createEmptyAnalytics(role, empId, "No view for this role", designation);
        }
    }
 
   
    // --- "NORMAL" ROUTER METHOD (Unchanged) ---
   
    /**
     * This is the original "normal" view for DGM, Zonal, or PRO.
     * It shows data for *only* their direct entity.
     */
   public CombinedAnalyticsDTO getAnalyticsForEmployee(Integer empId) {
       
    List<SCEmployeeEntity> employeeList = scEmployeeRepository.findByEmpId(empId);
   
    // Employee not found
    if (employeeList.isEmpty()) {
        System.err.println("No employee found with ID: " + empId);
        // Pass "N/A" for designation
        return createEmptyAnalytics("Invalid Employee", empId, "Employee not found", "N/A");
    }
   
    SCEmployeeEntity employee = employeeList.get(0);
    String role = employee.getEmpStudApplicationRole();
    String designation = employee.getDesignationName(); // <--- GET DESIGNATION HERE
   
    // Null role
    if (role == null) {
         System.err.println("Employee " + empId + " has a null role.");
         return createEmptyAnalytics("Null Role", empId, "Employee has no role", designation); // <--- PASS DESIGNATION
    }
   
    String trimmedRole = role.trim();
    CombinedAnalyticsDTO analytics;
   
    if (trimmedRole.equalsIgnoreCase("DGM")) {
        analytics = getDgmAnalytics(empId);
        analytics.setRole("DGM");
        analytics.setEntityName(employee.getFirstName() + " " + employee.getLastName());
        analytics.setEntityId(empId);
       
    } else if (trimmedRole.equalsIgnoreCase("ZONAL ACCOUNTANT")) {
        int zoneId = employee.getZoneId();
        analytics = getZoneAnalytics((long) zoneId);
        analytics.setRole("Zonal Account");
        analytics.setEntityName(employee.getZoneName());
        analytics.setEntityId(zoneId);
       
    } else if (trimmedRole.equalsIgnoreCase("PRO")) {
        int campusId = employee.getEmpCampusId();
        analytics = getCampusAnalytics((long) campusId);
        analytics.setRole("PRO");
        analytics.setEntityName(employee.getCampusName());
        analytics.setEntityId(campusId);
       
    } else {
        System.err.println("Unrecognized role '" + role + "' for empId: " + empId);
        return createEmptyAnalytics(role, empId, "Unrecognized role", designation); // <--- PASS DESIGNATION
    }
   
    // <--- SET DESIGNATION BEFORE RETURNING --->
    analytics.setDesignationName(designation);
    return analytics;
}
 
    private CombinedAnalyticsDTO createEmptyAnalytics(String role, Integer id, String name, String designationName) {
        CombinedAnalyticsDTO analytics = new CombinedAnalyticsDTO();
        analytics.setRole(role);
        analytics.setDesignationName(designationName); // <--- Set designation here
        analytics.setEntityId(id);
        analytics.setEntityName(name);
        return analytics;
    }
    // --- CORE ANALYTICS METHODS (Unchanged) ---
 
    public CombinedAnalyticsDTO getZoneAnalytics(Long zoneId) {
        CombinedAnalyticsDTO analytics = new CombinedAnalyticsDTO();
        
        // Convert Long to Integer for the Repo calls that need it
        Integer zoneIdInt = zoneId.intValue();

        analytics.setGraphData(getGraphData(
            (yearId) -> userAppSoldRepository.getSalesSummaryByZone(zoneIdInt, yearId),
            () -> userAppSoldRepository.findDistinctYearIdsByZone(zoneIdInt)
        ));
        
        analytics.setMetricsData(
            getMetricsData(
                (yearId) -> appStatusTrackRepository.getMetricsByZoneAndYear(zoneId, yearId),
                
                // CHANGE IS HERE: Use AppStatusTrack repo (filter by appIssuedId=4)
                (yearId) -> appStatusTrackRepository.getProMetricByZoneId_FromStatus(zoneIdInt, yearId),
                
                () -> appStatusTrackRepository.findDistinctYearIdsByZone(zoneId)
            )
        );
        return analytics;
    }
 
    public CombinedAnalyticsDTO getDgmAnalytics(Integer dgmEmpId) {
        CombinedAnalyticsDTO analytics = new CombinedAnalyticsDTO();
        analytics.setGraphData(getGraphData(
            (yearId) -> userAppSoldRepository.getSalesSummaryByDgm(dgmEmpId, yearId),
            () -> userAppSoldRepository.findDistinctYearIdsByDgm(dgmEmpId)
        ));
        analytics.setMetricsData(
            getMetricsData(
                (yearId) -> appStatusTrackRepository.getMetricsByEmployeeAndYear(dgmEmpId, yearId),
                (yearId) -> userAppSoldRepository.getProMetricByDgm(dgmEmpId, yearId),
                () -> appStatusTrackRepository.findDistinctYearIdsByEmployee(dgmEmpId)
            )
        );
        return analytics;
    }
    
 // In AnalyticsService.java

public CombinedAnalyticsDTO getEmployeeAnalytics(Long empId) {
    Integer empIdInt = empId.intValue(); 

    Dgm dgmRecord = dgmRepository.lookupByEmpId(empIdInt) 
        .orElseThrow(() -> new RuntimeException("DGM record not found for Employee ID: " + empId));
    
    Integer cmpsId = dgmRecord.getCampus().getCampusId(); 
    
    CombinedAnalyticsDTO analytics = new CombinedAnalyticsDTO();

    analytics.setGraphData(getGraphData(
        (yearId) -> userAppSoldRepository.getSalesSummaryByCampusIdAndYear(cmpsId, yearId),
        () -> userAppSoldRepository.findDistinctYearIdsByCampusId(cmpsId)
    ));
    analytics.setMetricsData(
        getMetricsData(
            (yearId) -> appStatusTrackRepository.getMetricsByCampusIdAndYear(cmpsId, yearId), 
            (yearId) -> appStatusTrackRepository.getProMetricByCampusId_FromStatus(cmpsId, yearId),
            () -> appStatusTrackRepository.findDistinctYearIdsByCampusId(cmpsId)
        )
    );
    
    return analytics;
}
 
    public CombinedAnalyticsDTO getCampusAnalytics(Long campusId) {
        CombinedAnalyticsDTO analytics = new CombinedAnalyticsDTO();
        
        // Convert Long to Integer for the Repo calls
        Integer campusIdInt = campusId.intValue(); 

        analytics.setGraphData(getGraphData(
            (yearId) -> userAppSoldRepository.getSalesSummaryByCampus(campusIdInt, yearId),
            () -> userAppSoldRepository.findDistinctYearIdsByCampus(campusIdInt)
        ));
        
        analytics.setMetricsData(
            getMetricsData(
                (yearId) -> appStatusTrackRepository.getMetricsByCampusAndYear(campusId, yearId),
                
                // CHANGE IS HERE: Use AppStatusTrack repo
                (yearId) -> appStatusTrackRepository.getProMetricByCampusId_FromStatus(campusIdInt, yearId),
                
                () -> appStatusTrackRepository.findDistinctYearIdsByCampus(campusId)
            )
        );
        return analytics;
    }
   
public GraphDTO getGraphDataByZoneIdAndAmount(Integer zoneId, Float amount) {
       
        if (zoneId == null || amount == null) {
            GraphDTO emptyGraph = new GraphDTO();
            emptyGraph.setTitle("Error: Zone ID and Amount must be provided.");
            emptyGraph.setYearlyData(new ArrayList<>());
            return emptyGraph;
        }
       
        // This leverages the generic getGraphData helper with new repository functions
        return getGraphData(
            // Data Fetcher: Function<Integer, Optional<GraphSoldSummaryDTO>> (takes yearId)
            (yearId) -> userAppSoldRepository.getSalesSummaryByZoneAndAmount(zoneId, yearId, amount),
           
            // Year Fetcher: Supplier<List<Integer>> (takes no arguments)
            () -> userAppSoldRepository.findDistinctYearIdsByZoneAndAmount(zoneId, amount)
        );
    }

public GraphDTO getGraphDataByCampusIdAndAmount(Integer campusId, Float amount) {
   
    if (campusId == null || amount == null) {
        GraphDTO emptyGraph = new GraphDTO();
        emptyGraph.setTitle("Error: Campus ID and Amount must be provided.");
        emptyGraph.setYearlyData(new ArrayList<>());
        return emptyGraph;
    }

    // This leverages the generic getGraphData helper with new repository functions
    return getGraphData(
        // Data Fetcher: Function<Integer, Optional<GraphSoldSummaryDTO>> (takes yearId)
        (yearId) -> userAppSoldRepository.getSalesSummaryByCampusAndAmount(campusId, yearId, amount),
       
        // Year Fetcher: Supplier<List<Integer>> (takes no arguments)
        () -> userAppSoldRepository.findDistinctYearIdsByCampusAndAmount(campusId, amount)
    );
}
 
private CombinedAnalyticsDTO getDgmDirectAnalytics(SCEmployeeEntity employee) {
        int empId = employee.getEmpId();

        // 1. Fetch DGM Record to get Campus ID
        // Assuming findByEmployee_EmpId returns List or Optional. Taking first for safety.
        Dgm dgmRecord = dgmRepository.lookupByEmpId(empId).orElse(null);

        if (dgmRecord == null || dgmRecord.getCampus() == null) {
            return createEmptyAnalytics("DGM", empId, "DGM not mapped to a Campus", employee.getDesignationName());
        }

        int campusId = dgmRecord.getCampus().getCampusId(); // Pick Campus ID
        String campusName = dgmRecord.getCampus().getCampusName(); // Assuming you have name in Campus entity

        // 2. Get Data using Campus ID directly
        CombinedAnalyticsDTO analytics = new CombinedAnalyticsDTO();
        
        // Use the new Repo methods created in Step 1
        analytics.setGraphData(getGraphDataForCampus(campusId));
        analytics.setMetricsData(getMetricsDataForCampus(campusId));

        // 3. Set Header Info
        analytics.setRole("DGM");
        analytics.setDesignationName(employee.getDesignationName());
        analytics.setEntityName(campusName); // Showing Campus Name
        analytics.setEntityId(campusId);

        return analytics;
    }

    /**
     * PRIVATE: Gets analytics for a Zonal Accountant's *managed DGMs*.
     */
private CombinedAnalyticsDTO getZonalDirectAnalytics(SCEmployeeEntity employee) {
        int empId = employee.getEmpId();

        // 1. Fetch ZonalAccountant Record to get Zone ID
        ZonalAccountant zonalRecord = zonalAccountantRepository.lookupByEmpId(empId).orElse(null);
        if (zonalRecord == null || zonalRecord.getZone() == null) {
            return createEmptyAnalytics("Zonal Accountant", empId, "Not mapped to a Zone", employee.getDesignationName());
        }

        int zoneId = zonalRecord.getZone().getZoneId(); // Pick Zone ID
        String zoneName = zonalRecord.getZone().getZoneName(); 

        // 2. Get Data using Zone ID directly
        CombinedAnalyticsDTO analytics = new CombinedAnalyticsDTO();

        // Use the new Repo methods created in Step 1
        analytics.setGraphData(getGraphDataForZone(zoneId));
        analytics.setMetricsData(getMetricsDataForZone(zoneId));

        // 3. Set Header Info
        analytics.setRole("Zonal Accountant");
        analytics.setDesignationName(employee.getDesignationName());
        analytics.setEntityName(zoneName); // Showing Zone Name
        analytics.setEntityId(zoneId);

        return analytics;
    }
   
    // --- PRIVATE HELPER METHODS for ROLLUPS (Unchanged) ---
   
//=========================================================================
//  DGM / CAMPUS DIRECT HELPERS
// =========================================================================

private GraphDTO getGraphDataForCampus(Integer campusId) {
    return getGraphData(
        // Lambda passes 'yearId' to the repo method
        (yearId) -> userAppSoldRepository.getSalesSummaryByCampusIdAndYear(campusId, yearId),
        // Supplier gets distinct years
        () -> userAppSoldRepository.findDistinctYearIdsByCampusId(campusId)
    );
}

private MetricsDataDTO getMetricsDataForCampus(Integer campusId) {
    return getMetricsData(
        // 1. Main Metrics (Total, Issued, Damaged, etc.)
        (yearId) -> appStatusTrackRepository.getMetricsByCampusIdAndYear(campusId, yearId),
        // 2. Pro Metric (Sold count specifically for the card)
        (yearId) -> appStatusTrackRepository.getProMetricByCampusId_FromStatus(campusId, yearId),
        // 3. Distinct Years
        () -> appStatusTrackRepository.findDistinctYearIdsByCampusId(campusId)
    );
}

// =========================================================================
//  ZONAL / ZONE DIRECT HELPERS
// =========================================================================

private GraphDTO getGraphDataForZone(Integer zoneId) {
    return getGraphData(
        // Lambda passes 'yearId' to the repo method
        (yearId) -> userAppSoldRepository.getSalesSummaryByZoneIdAndYear(zoneId, yearId),
        // Supplier gets distinct years
        () -> userAppSoldRepository.findDistinctYearIdsByZoneId(zoneId)
    );
}

private MetricsDataDTO getMetricsDataForZone(Integer zoneId) {
    return getMetricsData(
        // 1. Main Metrics
        (yearId) -> appStatusTrackRepository.getMetricsByZoneIdAndYear(zoneId, yearId),
        // 2. Pro Metric
        (yearId) -> appStatusTrackRepository.getProMetricByZoneId_FromStatus(zoneId, yearId),
        // 3. Distinct Years
        () -> appStatusTrackRepository.findDistinctYearIdsByZoneId(zoneId)
    );
}
 
    // --- Private Graph Data Helper (Unchanged) ---
 
    private GraphDTO getGraphData(
            Function<Integer, Optional<GraphSoldSummaryDTO>> dataFetcher,
            Supplier<List<Integer>> yearFetcher) {
       
        GraphDTO graphData = new GraphDTO();
        List<YearlyGraphPointDTO> yearlyDataList = new ArrayList<>();
 
        try {
            List<Integer> existingYearIds = yearFetcher.get();
 
            List<AcademicYear> academicYears = academicYearRepository.findByAcdcYearIdIn(existingYearIds)
                    .stream()
                    .sorted(Comparator.comparingInt(AcademicYear::getAcdcYearId))
                    .toList();
 
            for (AcademicYear year : academicYears) {
                int acdcYearId = year.getAcdcYearId();
                String yearLabel = year.getAcademicYear();
 
                GraphSoldSummaryDTO summary = dataFetcher.apply(acdcYearId)
                        .orElse(new GraphSoldSummaryDTO(0L, 0L));
 
                long issued = summary.totalApplications();
                long sold = summary.totalSold();
 
                double issuedPercent = issued > 0 ? 100.0 : 0.0;
                double soldPercent = (issued > 0)
                        ? Math.min(100.0, ((double) sold / issued) * 100.0)
                        : 0.0;
 
                yearlyDataList.add(new YearlyGraphPointDTO(
                        yearLabel, issuedPercent, soldPercent, issued, sold
                ));
            }
 
            if (!academicYears.isEmpty()) {
                graphData.setTitle("Application Sales Percentage (" +
                        academicYears.get(0).getAcademicYear() + "–" +
                        academicYears.get(academicYears.size() - 1).getAcademicYear() + ")");
            } else {
                graphData.setTitle("Application Sales Percentage (No Data)");
            }
 
        } catch (Exception e) {
            System.err.println("Error fetching graph data: " + e.getMessage());
            e.printStackTrace();
        }
 
        graphData.setYearlyData(yearlyDataList);
        return graphData;
    }
 
    // --- Private Metrics Data Helper (Unchanged) ---
 
    private MetricsDataDTO getMetricsData(
            Function<Integer, Optional<MetricsAggregateDTO>> dataFetcher,
            Function<Integer, Optional<Long>> proFetcher,
            Supplier<List<Integer>> yearFetcher) {

        MetricsDataDTO dto = new MetricsDataDTO();

        try {

            List<Integer> yearIds = yearFetcher.get();

            if (yearIds.isEmpty()) {
                dto.setMetrics(new ArrayList<>());
                return dto;
            }

            // Sort yearIds ascending → last one is current year
            yearIds.sort(Integer::compare);

            int currentYearId = yearIds.get(yearIds.size() - 1);
            int previousYearId = (yearIds.size() > 1)
                    ? yearIds.get(yearIds.size() - 2)
                    : currentYearId;

            AcademicYear cy = academicYearRepository.findById(currentYearId).orElse(null);
            AcademicYear py = academicYearRepository.findById(previousYearId).orElse(null);

            dto.setCurrentYear(cy != null ? cy.getYear() : 0);
            dto.setPreviousYear(py != null ? py.getYear() : 0);

            MetricsAggregateDTO curr = dataFetcher.apply(currentYearId)
                    .orElse(new MetricsAggregateDTO());
            MetricsAggregateDTO prev = dataFetcher.apply(previousYearId)
                    .orElse(new MetricsAggregateDTO());

            long proCurr = proFetcher.apply(currentYearId).orElse(0L);
            long proPrev = proFetcher.apply(previousYearId).orElse(0L);

            MetricsAggregateDTO totalMetrics = curr;   // instead of summing every year
            long totalPro = proCurr;
            // ------------------------------------------------------

            List<MetricDTO> cards = buildMetricsList(curr, prev, totalMetrics, proCurr, proPrev, totalPro);

            dto.setMetrics(cards);

        } catch (Exception ex) {
            System.out.println("🔥 METRICS ERROR: " + ex.getMessage());
            ex.printStackTrace();
            dto.setMetrics(new ArrayList<>());
        }

        return dto;
    }

    /**
     * Builds the metrics list.
     */
    private List<MetricDTO> buildMetricsList(
            MetricsAggregateDTO current, MetricsAggregateDTO previous, MetricsAggregateDTO total,
            long proCurrent, long proPrevious, long totalPro) {
       
        List<MetricDTO> metrics = new ArrayList<>();
 
        metrics.add(createMetric("Total Applications",
            total.totalApp(),
            current.totalApp(), previous.totalApp()));
 
        double soldPercentCurrent = calculatePercentage(current.appSold(), current.totalApp());
        double soldPercentPrevious = calculatePercentage(previous.appSold(), previous.totalApp());
        metrics.add(createMetricWithPercentage("Sold",
            total.appSold(),
            soldPercentCurrent, soldPercentPrevious));
 
        double confirmedPercentCurrent = calculatePercentage(current.appConfirmed(), current.totalApp());
        double confirmedPercentPrevious = calculatePercentage(previous.appConfirmed(), previous.totalApp());
        metrics.add(createMetricWithPercentage("Confirmed",
            total.appConfirmed(),
            confirmedPercentCurrent, confirmedPercentPrevious));
       
        metrics.add(createMetric("Available",
            total.appAvailable(),
            current.appAvailable(), previous.appAvailable()));
 
        long validIssuedCurrent = Math.max(0, current.appIssued());
        long validIssuedPrevious = Math.max(0, previous.appIssued());
        double issuedPercentCurrent = calculatePercentage(validIssuedCurrent, current.totalApp());
        double issuedPercentPrevious = calculatePercentage(validIssuedPrevious, previous.totalApp());
        metrics.add(createMetricWithPercentage("Issued",
            total.appIssued(),
            issuedPercentCurrent, issuedPercentPrevious));
 
        metrics.add(createMetric("Damaged",
            total.appDamaged(),
            current.appDamaged(), previous.appDamaged()));
           
        metrics.add(createMetric("Unavailable",
            total.appUnavailable(),
            current.appUnavailable(), previous.appUnavailable()));
       
        metrics.add(createMetric("With PRO",
            totalPro,
            proCurrent, proPrevious));
 
        return metrics;
    }
 
    // --- UTILITY METHODS ---
 
    private MetricDTO createMetric(String title, long totalValue, long currentValue, long previousValue) {
        double change = calculatePercentageChange(currentValue, previousValue);
        return new MetricDTO(title, totalValue, change, getChangeDirection(change));
    }
 
    private MetricDTO createMetricWithPercentage(String title, long totalValue, double currentPercent, double previousPercent) {
        double change = calculatePercentageChange(currentPercent, previousPercent);
        return new MetricDTO(title, totalValue, change, getChangeDirection(change));
    }
 
    private double calculatePercentage(long numerator, long denominator) {
        if (denominator == 0) return 0.0;
        return (double) Math.max(0, numerator) * 100.0 / denominator;
    }
 
    private double calculatePercentageChange(double current, double previous) {
        if (previous == 0) return (current > 0) ? 100 : 0;
        double change = ((current - previous) / previous) * 100;
        return Math.round(change);
    }
   
    private String getChangeDirection(double change) {
        if (change > 0) return "up";
        if (change < 0) return "down";
        return "neutral";
    }
 
    private int getAcdcYearId(int year) {
        return academicYearRepository.findByYear(year)
                .map(AcademicYear::getAcdcYearId)
                .orElse(0);
    }
   
    // --- NEW: Flexible Graph Data Method with Optional Filters ---
   
    /**
     * Get year-wise graph data (GraphBarDTO) with optional filters for zoneId, campusId, and amount.
     * All parameters are optional. Always returns data for the past 4 years (current + 3 previous).
     * If data doesn't exist for a year, returns 0 values for that year.
     *
     * @param zoneId Optional zone ID filter
     * @param campusId Optional campus ID filter
     * @param amount Optional amount filter
     * @return List of GraphBarDTO containing year-wise issued and sold data for past 4 years
     */
    public List<GraphBarDTO> getFlexibleGraphData(Integer zoneId, Integer campusId, Float amount) {
        // Get current year (latest year) from AppStatusTrackRepository
        Integer currentYearId = appStatusTrackRepository.findLatestYearId();
        if (currentYearId == null) {
            return new ArrayList<>();
        }
       
        // Get previous 4 years (current year + 3 previous years)
        List<Integer> yearIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            yearIds.add(currentYearId - i);
        }
       
        List<Object[]> rows;
       
        // Determine which repository method to call based on provided parameters
        if (zoneId != null && campusId != null && amount != null) {
            // All three filters - need to filter by yearIds manually
            System.out.println("Using filter: Zone + Campus + Amount");
            rows = userAppSoldRepository.getYearWiseIssuedAndSoldByZoneCampusAndAmount(zoneId, campusId, amount);
            rows = rows.stream()
                    .filter(row -> yearIds.contains((Integer) row[0]))
                    .collect(java.util.stream.Collectors.toList());
        } else if (zoneId != null && amount != null) {
            // Zone + Amount - need to filter by yearIds manually
            System.out.println("Using filter: Zone + Amount (zoneId=" + zoneId + ", amount=" + amount + ")");
            rows = userAppSoldRepository.getYearWiseIssuedAndSoldByZoneAndAmount(zoneId, amount);
            rows = rows.stream()
                    .filter(row -> yearIds.contains((Integer) row[0]))
                    .collect(java.util.stream.Collectors.toList());
        } else if (campusId != null && amount != null) {
            // Campus + Amount - need to filter by yearIds manually
            rows = userAppSoldRepository.getYearWiseIssuedAndSoldByCampusAndAmount(campusId, amount);
            rows = rows.stream()
                    .filter(row -> yearIds.contains((Integer) row[0]))
                    .collect(java.util.stream.Collectors.toList());
        } else if (zoneId != null) {
            // Zone only - need to filter by yearIds manually
            rows = userAppSoldRepository.getYearWiseIssuedAndSoldByZone(zoneId);
            rows = rows.stream()
                    .filter(row -> yearIds.contains((Integer) row[0]))
                    .collect(java.util.stream.Collectors.toList());
        } else if (campusId != null) {
            // Campus only - need to filter by yearIds manually
            rows = userAppSoldRepository.getYearWiseIssuedAndSoldByCampus(campusId);
            rows = rows.stream()
                    .filter(row -> yearIds.contains((Integer) row[0]))
                    .collect(java.util.stream.Collectors.toList());
        } else if (amount != null) {
            // Amount only - need to filter by yearIds manually
            rows = userAppSoldRepository.getYearWiseIssuedAndSoldByAmount(amount);
            rows = rows.stream()
                    .filter(row -> yearIds.contains((Integer) row[0]))
                    .collect(java.util.stream.Collectors.toList());
        } else {
            // No filters - get all data and filter by yearIds
            rows = userAppSoldRepository.getYearWiseIssuedAndSold();
            rows = rows.stream()
                    .filter(row -> yearIds.contains((Integer) row[0]))
                    .collect(java.util.stream.Collectors.toList());
        }
       
        // Get AcademicYear entities for year labels
        List<AcademicYear> academicYears = academicYearRepository.findByAcdcYearIdIn(yearIds);
        java.util.Map<Integer, AcademicYear> yearMap = academicYears.stream()
                .collect(java.util.stream.Collectors.toMap(AcademicYear::getAcdcYearId, y -> y));
       
        // Create a map of yearId -> [totalAppCount, sold] for quick lookup
        java.util.Map<Integer, long[]> yearDataMap = new java.util.HashMap<>();
        for (Object[] row : rows) {
            Integer yearId = (Integer) row[0];
            Long totalAppCount = row[1] != null ? ((Number) row[1]).longValue() : 0L;
            Long sold = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            yearDataMap.put(yearId, new long[]{totalAppCount, sold});
        }
       
        // Build GraphBarDTO list for all 4 years (always return 4 years)
        List<GraphBarDTO> barList = new ArrayList<>();
        for (Integer yearId : yearIds) {
            long[] data = yearDataMap.getOrDefault(yearId, new long[]{0L, 0L});
            long issuedCount = data[0]; // totalAppCount from table
            long soldCount = data[1];   // sold from table
           
            AcademicYear year = yearMap.get(yearId);
            String yearLabel = year != null ? year.getAcademicYear() : "Year " + yearId;
           
            // Calculate percentages
            int issuedPercent;
            int soldPercent;
           
            // If data is missing (issuedCount = 0), both percentages are 0
            // If data exists (issuedCount > 0), issuedPercent is 100% (baseline) and calculate sold percentage
            if (issuedCount > 0) {
                issuedPercent = 100; // 100% as baseline when data exists
                soldPercent = (int) Math.round((soldCount * 100.0) / issuedCount);
            } else {
                // No data exists - both percentages are 0
                issuedPercent = 0;
                soldPercent = 0;
            }
           
            GraphBarDTO dto = new GraphBarDTO();
            dto.setYear(yearLabel);
            dto.setIssuedPercent(issuedPercent);
            dto.setSoldPercent(soldPercent);
            dto.setIssuedCount((int) issuedCount);
            dto.setSoldCount((int) soldCount);
           
            barList.add(dto);
        }
       
        return barList;
    }
}
