package com.solid.connectgpu.port;

public interface DnsProvider {
    void createRecord(String hostname, String ip);
    void updateRecord(String hostname, String ip);
    void deleteRecord(String hostname);
}
