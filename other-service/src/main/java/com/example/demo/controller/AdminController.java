package com.example.demo.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * This controller intentionally contains a few common issues
 * (hardcoded credential, SQL built via string concatenation,
 * empty catch block) so SonarQube has real Bugs / Vulnerabilities /
 * Security Hotspots to report during the demo scan.
 */
@RestController
public class AdminController {

    // Sonar: hardcoded credential (security hotspot)
    private static final String ADMIN_PASSWORD = "admin123";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping("/api/admin/search")
    public List<Map<String, Object>> search(@RequestParam String name) {
        // Sonar: SQL injection vulnerability - string concatenation
        // instead of a parameterized query
        String sql = "SELECT * FROM item WHERE name = '" + name + "'";
        return jdbcTemplate.queryForList(sql);
    }

    @GetMapping("/api/admin/login")
    public boolean login(@RequestParam String password) {
        try {
            return password.equals(ADMIN_PASSWORD);
        } catch (Exception e) {
            // Sonar: empty catch block
        }
        return false;
    }
}
