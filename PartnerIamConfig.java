@Configuration
public class PartnerIamConfig {

    @Bean
    public WebClient partnerIamWebClient(
            WebClient.Builder builder) {

        return builder
                .baseUrl("https://api.corp.ford.com")
                .build();
    }
}
----------------------
partner.iam.base-url=https://api.corp.ford.com
partner.iam.groups-url=/usergroups/api/v2/groups-for-user
---------------------
@Service
@RequiredArgsConstructor
public class PartnerIamService {

    private final WebClient partnerIamWebClient;

    public Mono<String> getGroupsForUser(
            String userId,
            String accessToken) {

        return partnerIamWebClient
                .get()
                .uri("/usergroups/api/v2/groups-for-user")
                .header("userid", userId)
                .headers(headers ->
                    headers.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(String.class);
    }
}


-----------
@RestController
@RequestMapping("/internal/partner-iam")
@RequiredArgsConstructor
public class PartnerIamController {

    private final PartnerIamService partnerIamService;

    @GetMapping("/groups")
    public Mono<String> getGroups(
            @RequestParam String userId) {

        String accessToken = "TOKEN_FROM_TOKEN_SERVICE";

        return partnerIamService.getGroupsForUser(
                userId, accessToken);
    }
}