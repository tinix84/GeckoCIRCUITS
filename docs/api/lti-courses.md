
# LTI courses

GeckoCIRCUITS uses Learning Tools Interoperability (LTI) 1.3 to open a lab assignment from a learning management system (LMS).
An instructor can link an assignment. A learner can submit a circuit and send its score to the LMS gradebook.

## Configure the tool

1. Enable the assignments API.
2. Generate a private RSA JSON Web Key (JWK) with at least 2048 bits.
3. Give it a key ID.
4. Save it where only the API process can read it.
5. Set the LTI configuration.

```yaml
gecko:
  education:
    enabled: true
    directory: /var/lib/gecko/education
    lti:
      enabled: true
      base-url: https://gecko.example.edu/gecko
      signing-jwk: /run/secrets/gecko-lti-private.jwk
      registrations:
        - issuer: https://lms.example.edu
          client-id: gecko-lab
          deployment-ids: [gecko-deployment]
          authorization-url: https://lms.example.edu/oidc/authorize
          jwks-url: https://lms.example.edu/oidc/jwks
          token-url: https://lms.example.edu/oauth/token
          return-origins: [https://lms.example.edu]
```

Use the platform URLs from the registration. Set `token-audience` if the platform specifies an audience that differs from its token URL.
The LTI tool uses HTTPS and an in-process session store. In an iframe, the browser must permit the LTI cookies.

## Register the tool in the LMS

Use these URLs relative to the configured `base-url`.

| Registration field | Path |
| --- | --- |
| OpenID Connect login initiation | `/api/v1/lti/login` |
| Launch and redirect URI | `/api/v1/lti/launch` |
| Public JWK Set URL | `/api/v1/lti/jwks` |

Enable Deep Linking and the Assignment and Grade Services score scope. Authorize `https://purl.imsglobal.org/spec/lti-ags/scope/score`.

## Create and link an assignment

1. Start a deep-link launch from the course as an instructor.
2. Copy the course tenant shown on the assignment picker if the course is empty.
3. Map a GeckoCIRCUITS ADMIN credential to that tenant with `gecko.api.tenants.<keyId>`.
4. Submit the assignment definition and reference circuit to `POST /api/v1/assignments`.
5. Start a new deep-link launch.
6. Select the assignment.
7. Return the signed link to the LMS.

The assignment request uses two multipart parts. Put the JSON in `assignment` with media type `application/json`. Put the reference `.ipes` in `file`.
The JSON takes a title, duration in seconds, time step in seconds, and checks with a signal, metric, time window, target, and absolute tolerance.

```json
{
  "title": "Buck converter",
  "duration": 0.004,
  "timeStep": 0.000001,
  "checks": [
    {"signal": "u2", "metric": "mean", "t0": 0.0029, "t1": 0.0039, "target": 18.82, "tolerance": 0.3764}
  ]
}
```

A learner uploads an `.ipes` file from the assignment launch. GeckoCIRCUITS grades the file and saves the result. The launch must supply a line item and score permission for gradebook delivery.

[1EdTech LTI 1.3](https://www.imsglobal.org/spec/lti/v1p3) · [Deep Linking](https://www.imsglobal.org/spec/lti-dl/v2p0) · [Assignment and Grade Services](https://www.imsglobal.org/spec/lti-ags/v2p0)
