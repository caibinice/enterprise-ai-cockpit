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
        DataSourceResponse ds = repository.findDataSource(id).orElseThrow(() -> new IllegalArgumentException("??????: " + id));
        return Map.of("status", "ok", "name", ds.name(), "type", ds.type(), "message", "MVP ????????????/JDBC ?????????????=" + ds.endpoint());
    }

    public String snapshot(String dataSourceKey) {
        return "[{\"region\":\"??\",\"amount\":120},{\"region\":\"??\",\"amount\":95},{\"region\":\"??\",\"amount\":88}]";
    }
}
