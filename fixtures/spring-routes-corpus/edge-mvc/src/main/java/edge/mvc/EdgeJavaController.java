package edge.mvc;

import static edge.mvc.JavaPaths.CONST;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.service.annotation.GetExchange;

@RestController
@RequestMapping({"/j", "java"})
public class EdgeJavaController {
    private static final String LOCAL = "/local";

    @GetMapping({"/a", "b"})
    public String cartesian() { return ""; }

    @RequestMapping(path = "/any")
    public String any() { return ""; }

    @RequestMapping(path = "/multi", method = {RequestMethod.GET, RequestMethod.POST})
    public String multi() { return ""; }

    @GetMapping("/users/{id:\\d+}")
    public String user() { return ""; }

    @GetMapping("/uuid/{id:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}}")
    public String uuid() { return ""; }

    @GetMapping("/files/{*path}")
    public String files() { return ""; }

    @GetMapping("/static/**")
    public String statics() { return ""; }

    @GetMapping(value = "/narrow", params = "x=1")
    public String narrow() { return ""; }

    @PostMapping(path = "/consume", consumes = "application/json")
    public String consume() { return ""; }

    @GetMapping(CONST + LOCAL)
    public String constant() { return ""; }

    @GetMapping("${app.version-prefix:/v1}/ver")
    public String placeholderDefault() { return ""; }

    @GetMapping("${app.items:/items}")
    public String placeholderValue() { return ""; }

    @GetMapping("${app.other:/fallback}")
    public String placeholderOtherProfile() { return ""; }

    @GetMapping("/doc/{name}.json")
    public String partial() { return ""; }

    @GetMapping("/x/*/y")
    public String wildcard() { return ""; }

    @GetMapping("/slug/{slug:[a-z0-9-]+}")
    public String slug() { return ""; }

    @GetMapping("/re/{code:[A-Z]{3}}")
    public String regex() { return ""; }

    @GetMapping("/trailing/")
    public String trailing() { return ""; }

    @JsonGet("/composed")
    public String composed() { return ""; }

    @GetExchange("/exchange")
    public String exchange() { return ""; }

    @GetMapping("/v{major}.{minor}")
    public String twoVariables() { return ""; }

    @GetMapping("/over")
    public String over(String a) { return ""; }

    @PostMapping("/over")
    public String over(Integer b) { return ""; }
}
