package com.example.aiagent.parking;

import com.example.aiagent.security.ActionAuthService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Parking principals are separate from global cockpit admin tokens; roles are server-signed. */
@Service
@ConditionalOnProperty(prefix="app.parking",name="enabled",havingValue="true")
public class ParkingAccessService {
    public record Principal(String username, String role) {
        public boolean staff() { return Set.of("security","operator","admin").contains(role); }
        public boolean operator() { return Set.of("operator","admin").contains(role); }
    }
    private final JdbcTemplate jdbc;
    private final ActionAuthService admin;
    private final String secret;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
    private final Map<String,Attempt> attempts = new LinkedHashMap<>();
    private record Attempt(long at,int count) {}
    public ParkingAccessService(JdbcTemplate jdbc,ActionAuthService admin,@Value("${app.action-auth.token-secret:}") String secret) {
        this.jdbc=jdbc;this.admin=admin;this.secret=secret;
    }
    public Map<String,Object> login(String username,String password,String client) {
        checkRate(client);
        String name=username==null?"":username.trim();
        if ("visitor".equals(name)) return issue(new Principal("visitor","visitor"));
        if ("admin".equals(name)) return Map.of("token",admin.verifyAndIssue(password),"role","admin","username","admin","expiresAt",Instant.now().plusSeconds(admin.ttlSeconds()).getEpochSecond());
        var users=jdbc.queryForList("SELECT role,password_hash FROM parking_users WHERE username=? AND enabled=TRUE",name);
        if(users.size()!=1 || password==null || !encoder.matches(password,String.valueOf(users.get(0).get("password_hash"))))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"账号或密码错误");
        return issue(new Principal(name,String.valueOf(users.get(0).get("role"))));
    }
    private synchronized void checkRate(String client) {
        long now=Instant.now().getEpochSecond();attempts.entrySet().removeIf(e->now-e.getValue().at()>60);
        String key=client==null?"unknown":client.substring(0,Math.min(client.length(),100));
        Attempt previous=attempts.get(key);int count=previous==null?1:previous.count()+1;
        if(count>10)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"登录尝试过于频繁，请稍后重试");
        if(attempts.size()>256)attempts.remove(attempts.keySet().iterator().next());
        attempts.put(key,new Attempt(previous==null?now:previous.at(),count));
    }
    public Principal principal(String authorization) {
        if(admin.authorized(authorization))return new Principal("admin","admin");
        if(authorization==null||!authorization.startsWith("Bearer p1."))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"停车会话已过期，请重新登录");
        try{
            String[] bits=authorization.substring(7).split("\\.");
            if(bits.length!=5 || Long.parseLong(bits[1])<=Instant.now().getEpochSecond())throw new IllegalArgumentException();
            String payload=String.join(".",Arrays.copyOf(bits,4));
            if(!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8),bits[4].getBytes(StandardCharsets.UTF_8)))throw new IllegalArgumentException();
            String username=new String(Base64.getUrlDecoder().decode(bits[2]),StandardCharsets.UTF_8),role=bits[3];
            if(!Set.of("visitor","security","operator").contains(role))throw new IllegalArgumentException();
            if(!"visitor".equals(role) && jdbc.queryForObject("SELECT COUNT(*) FROM parking_users WHERE username=? AND role=? AND enabled=TRUE",Integer.class,username,role)!=1)throw new IllegalArgumentException();
            return new Principal(username,role);
        }catch(Exception error){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"停车会话无效，请重新登录");}
    }
    private Map<String,Object> issue(Principal principal) {
        if(secret.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"停车会话签名尚未配置");
        long expires=Instant.now().plusSeconds(1800).getEpochSecond();
        String body="p1."+expires+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(principal.username().getBytes(StandardCharsets.UTF_8))+"."+principal.role();
        return Map.of("token",body+"."+sign(body),"username",principal.username(),"role",principal.role(),"expiresAt",expires);
    }
    private String sign(String value) {
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception error){throw new IllegalStateException("Parking token signing failed",error);}
    }
    public void require(Principal principal,String capability) {
        boolean allowed=switch(capability){case "admin"->"admin".equals(principal.role());case "analytics","approve"->principal.operator();case "workorder","audit"->principal.staff();default->true;};
        if(!allowed)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前角色没有此业务权限");
    }
    public void createUser(Principal actor,String username,String displayName,String role,String password) {
        require(actor,"admin");
        if(username==null||!username.matches("[a-zA-Z][a-zA-Z0-9_-]{2,39}")||Set.of("admin","visitor").contains(username)||role==null||!Set.of("security","operator").contains(role)||displayName==null||displayName.isBlank()||displayName.length()>100||password==null||password.length()<12||password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new IllegalArgumentException("请提供有效账号与角色；密码至少12字符，UTF-8编码不超过72字节");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM parking_users WHERE username=?",Integer.class,username)>0)return;
        jdbc.update("INSERT INTO parking_users(username,display_name,role,password_hash,enabled) VALUES(?,?,?,?,TRUE)",username,displayName,role,encoder.encode(password));
    }
}
