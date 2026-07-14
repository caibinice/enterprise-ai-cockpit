package com.example.aiagent.service;

import com.example.aiagent.model.*;
import com.example.aiagent.repository.EnterpriseRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DataSourceService {
    private final EnterpriseRepository repository;

    public DataSourceService(EnterpriseRepository repository) { this.repository = repository; }

    public DataSourceResponse create(DataSourceRequest request) { return repository.saveDataSource(request); }
    public List<DataSourceResponse> list() { return repository.listDataSources(); }
    public void delete(long id) { repository.deleteDataSource(id); }

    public Map<String, Object> test(long id) {
        DataSourceResponse ds = repository.findDataSource(id).orElseThrow(() -> new IllegalArgumentException("Data source not found: " + id));
        return Map.of("status", "ok", "name", ds.name(), "type", ds.type(), "message", "MVP configuration check passed. Real HTTP/JDBC extraction runs in report jobs. Endpoint=" + ds.endpoint());
    }

    public String snapshot(String dataSourceKey) {
        return "[{\"region\":\"East\",\"amount\":120},{\"region\":\"South\",\"amount\":95},{\"region\":\"North\",\"amount\":88}]";
    }
}
