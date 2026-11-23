package com.edupulse.ingeststatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import com.edupulse.ingeststatic.chunking.StructureAwareChunker;
import com.edupulse.ingeststatic.chunking.TextChunk;
import com.edupulse.ingeststatic.pdf.PageText;
import com.edupulse.ingeststatic.pdf.PdfTextExtractor;
import com.edupulse.ingeststatic.pdf.TextCleaner;

/**
 * Generates a realistic cybersecurity sample set of PDFs in {@code resources/}:
 * <ol>
 *   <li>{@code cybersecurity-policy-iso27001.pdf} — multi-page corporate security &amp; compliance policy</li>
 *   <li>{@code security-controls-audit-tables.pdf} — multi-page audit report with structured tables</li>
 *   <li>{@code zero-trust-architecture-twocolumn.pdf} — two-column technical research paper</li>
 * </ol>
 */
public final class CybersecuritySamplePdfGenerator {

    private CybersecuritySamplePdfGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path outDir = Path.of(args.length > 0 ? args[0] : "../resources");
        Files.createDirectories(outDir);

        Path policyPdf = outDir.resolve("cybersecurity-policy-iso27001.pdf");
        Path tablesPdf = outDir.resolve("security-controls-audit-tables.pdf");
        Path twoColPdf = outDir.resolve("zero-trust-architecture-twocolumn.pdf");

        writePolicyPdf(policyPdf);
        writeAuditTablesPdf(tablesPdf);
        writeTwoColumnPdf(twoColPdf);

        // Verify extraction + chunking with default production settings (800 / 100)
        PdfTextExtractor extractor = new PdfTextExtractor();
        StructureAwareChunker chunker = new StructureAwareChunker(800, 100);
        int totalChunks = 0;
        for (Path pdf : List.of(policyPdf, tablesPdf, twoColPdf)) {
            byte[] bytes = Files.readAllBytes(pdf);
            List<PageText> pages = TextCleaner.cleanPages(extractor.extract(bytes, pdf.getFileName().toString()));
            List<TextChunk> chunks = chunker.chunk(pages);
            totalChunks += chunks.size();
            System.out.printf("%s: %d bytes, %d pages, %d chunks%n",
                    pdf.getFileName(), bytes.length, pages.size(), chunks.size());
        }
        System.out.printf("TOTAL CHUNKS (800/100): %d%n", totalChunks);
    }

    private static void writePolicyPdf(Path target) throws IOException {
        List<List<String>> pages = List.of(
                List.of(
                        "1. Information Security Governance and Scope",
                        "This Information Security Policy defines the mandatory cybersecurity controls for all production "
                                + "workloads, corporate endpoints, cloud accounts, and third-party integrations operated by "
                                + "the organization. The policy aligns with ISO/IEC 27001:2022, the EU NIS2 Directive, and "
                                + "SOC 2 Type II trust services criteria. Every employee, contractor, and automated service "
                                + "account processing regulated data must comply with these requirements without exception.",
                        "1.1 Roles and Accountability",
                        "The Chief Information Security Officer (CISO) owns the security governance program, approves "
                                + "annual risk assessments, and reports residual risk metrics to the Audit Committee every "
                                + "quarter. System owners are responsible for applying security patches within SLA windows, "
                                + "maintaining an accurate asset inventory, and participating in tabletop incident drills.",
                        "2. Identity and Access Management",
                        "Access to all administrative consoles, source code repositories, CI/CD pipelines, and production "
                                + "databases must enforce phishing-resistant multi-factor authentication (FIDO2 or hardware "
                                + "security keys). Shared or generic privileged accounts are strictly prohibited except for "
                                + "documented break-glass accounts sealed in an audited secrets vault with real-time alerting.",
                        "2.1 Least Privilege and Access Reviews",
                        "Permissions are granted exclusively through Role-Based Access Control (RBAC) groups mapped to "
                                + "job functions. Privileged access to production environments must be Just-In-Time (JIT) "
                                + "with automatic expiration after four hours. Managers and compliance officers must complete "
                                + "quarterly entitlement reviews and revoke dormant accounts within twenty-four hours of "
                                + "employee offboarding."
                ),
                List.of(
                        "3. Cryptography and Key Management",
                        "All confidential and regulated data stored in relational databases, object storage buckets, "
                                + "vector stores, and off-site backups must be encrypted at rest using AES-256-GCM or an "
                                + "equivalent NIST-approved algorithm. Data in transit across both external networks and "
                                + "internal microservice meshes must use TLS 1.3 with strong forward-secret cipher suites.",
                        "3.1 Secret Hygiene and Key Rotation",
                        "Cryptographic keys, API tokens, database credentials, and webhook signing secrets must never "
                                + "be committed to version control repositories or embedded in container images. Automated "
                                + "secret scanning runs on every pull request and blocks merges containing leaked credentials. "
                                + "Master encryption keys managed in the Hardware Security Module (HSM) rotate annually, "
                                + "while service-to-service JWT signing keys rotate every ninety days.",
                        "4. Vulnerability Management and Secure Development",
                        "All software artifacts undergo Static Application Security Testing (SAST), Software Composition "
                                + "Analysis (SCA), and container image scanning prior to deployment. Critical vulnerabilities "
                                + "with a CVSS v3.1 score of 9.0 or higher must be remediated or mitigated within seventy-two "
                                + "hours. High-severity findings (CVSS 7.0 to 8.9) must be resolved within fourteen days, "
                                + "and medium-severity findings within thirty days.",
                        "4.1 Penetration Testing",
                        "An independent accredited security firm conducts external and internal penetration tests at "
                                + "least once per year and after any major architectural change. Findings are tracked to "
                                + "closure in the compliance ticketing system and verified via formal re-testing."
                ),
                List.of(
                        "5. Security Monitoring and Logging",
                        "Authentication events, privilege escalations, API gateway rejections, firewall blocks, and "
                                + "database schema modifications must be streamed in real time to the centralized SIEM "
                                + "platform. Logs are cryptographically hashed to guarantee tamper evidence and retained in "
                                + "immutable storage for a minimum of three hundred sixty-five days to support forensic "
                                + "investigations and regulatory audits.",
                        "6. Incident Response and Breach Notification",
                        "The Computer Security Incident Response Team (CSIRT) maintains a documented playbook covering "
                                + "preparation, detection, containment, eradication, recovery, and post-incident lessons "
                                + "learned. Suspected security incidents must be triaged within thirty minutes of alert "
                                + "generation and classified by severity from SEV-1 (active data exfiltration or ransomware) "
                                + "to SEV-4 (minor policy deviation).",
                        "6.1 Regulatory Reporting Timelines",
                        "Under GDPR Article 33 and NIS2 obligations, confirmed personal data breaches or significant "
                                + "service disruptions require an early warning notification to the national supervisory "
                                + "authority within twenty-four hours and a formal incident report within seventy-two hours. "
                                + "Affected customers and data controllers must be notified without undue delay whenever "
                                + "the breach poses a high risk to individual rights and freedoms."
                )
        );
        try (PDDocument doc = new PDDocument()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            for (int p = 0; p < pages.size(); p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    writeLine(cs, regular, 9, 50, 810, "EduPulse Security Governance - ISO/IEC 27001 Policy");
                    float y = 768;
                    for (String block : pages.get(p)) {
                        boolean heading = block.length() < 65 && !block.endsWith(".");
                        PDType1Font font = heading ? bold : regular;
                        float size = heading ? 12.5f : 10f;
                        for (String line : wrap(block, 84)) {
                            writeLine(cs, font, size, 50, y, line);
                            y -= (size + 4.2f);
                        }
                        y -= 10;
                    }
                    writeLine(cs, regular, 9, 275, 35, "Page " + (p + 1) + " of " + pages.size());
                }
            }
            doc.save(target.toFile());
        }
    }

    private static void writeAuditTablesPdf(Path target) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            // Page 1: Security Control Compliance Matrix
            PDPage page1 = new PDPage(PDRectangle.A4);
            doc.addPage(page1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                writeLine(cs, regular, 9, 50, 810, "Q4 Cybersecurity Controls Audit Report");
                writeLine(cs, bold, 14, 50, 770, "1. Security Controls Compliance Matrix");
                float y = 746;
                for (String line : wrap(
                        "This audit report summarizes the operational effectiveness of key technical security controls "
                                + "evaluated during the Q4 internal cybersecurity assessment. Each control is mapped to "
                                + "ISO 27001 Annex A or OWASP ASVS requirements and assigned a remediation priority.",
                        84)) {
                    writeLine(cs, regular, 10, 50, y, line);
                    y -= 14;
                }

                y -= 16;
                float[] colX1 = {50, 115, 275, 375, 455};
                String[][] table1 = {
                        {"Control", "Security Domain", "Standard", "Status", "Risk"},
                        {"CTL-01", "Phishing-Resistant MFA", "ISO A.8.5", "Compliant", "Low"},
                        {"CTL-02", "AES-256 Vector Encryption", "ISO A.8.24", "Compliant", "Low"},
                        {"CTL-03", "SSRF Egress Filtering", "OWASP A10", "Partial", "High"},
                        {"CTL-04", "Webhook HMAC Verification", "OWASP A08", "Compliant", "Medium"},
                        {"CTL-05", "JIT Privileged Access", "SOC2 CC6.1", "In Progress", "High"},
                        {"CTL-06", "Immutable SIEM Retention", "NIS2 Art.21", "Compliant", "Low"},
                        {"CTL-07", "Automated Secret Scanning", "OWASP A02", "Compliant", "Low"},
                        {"CTL-08", "SBOM & Dependency Pinning", "NIST SSDF", "Partial", "Medium"}
                };
                for (int r = 0; r < table1.length; r++) {
                    PDType1Font f = r == 0 ? bold : regular;
                    for (int c = 0; c < table1[r].length; c++) {
                        writeLine(cs, f, 9.5f, colX1[c], y, table1[r][c]);
                    }
                    y -= 20;
                }

                y -= 18;
                writeLine(cs, bold, 12, 50, y, "1.1 Summary of Open Audit Gaps");
                y -= 18;
                for (String line : wrap(
                        "Two high-risk findings require remediation before the next external surveillance audit. "
                                + "First, CTL-03 requires blocking RFC 1918 private IP ranges, loopback addresses, and "
                                + "cloud metadata endpoints (169.254.169.254) inside the dynamic ingestion pipeline. "
                                + "Second, CTL-05 requires replacing static database administrator roles with time-bound "
                                + "Just-In-Time credentials approved through the identity provider.",
                        84)) {
                    writeLine(cs, regular, 10, 50, y, line);
                    y -= 14;
                }
                writeLine(cs, regular, 9, 275, 35, "Page 1 of 2");
            }

            // Page 2: Cryptographic Standards & Remediation SLA Table
            PDPage page2 = new PDPage(PDRectangle.A4);
            doc.addPage(page2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                writeLine(cs, regular, 9, 50, 810, "Q4 Cybersecurity Controls Audit Report");
                writeLine(cs, bold, 14, 50, 770, "2. Cryptographic Standards and Key Rotation Schedule");
                float y = 746;
                for (String line : wrap(
                        "The table below defines approved cryptographic primitives, minimum key lengths, rotation "
                                + "intervals, and responsible engineering teams across all regulated environments.",
                        84)) {
                    writeLine(cs, regular, 10, 50, y, line);
                    y -= 14;
                }

                y -= 16;
                float[] colX2 = {50, 175, 295, 385, 470};
                String[][] table2 = {
                        {"Asset Class", "Algorithm", "Key Size", "Rotation", "Owner"},
                        {"PostgreSQL Data", "AES-256-GCM", "256-bit", "365 days", "Platform"},
                        {"Gateway TLS", "TLS 1.3 ECDHE", "P-256", "90 days", "NetSec"},
                        {"JWT Access Tokens", "RS256 / EdDSA", "2048-bit", "90 days", "IAM Team"},
                        {"Webhook Signatures", "HMAC-SHA256", "256-bit", "180 days", "AppSec"},
                        {"Backup Archives", "AES-256-GCM", "256-bit", "365 days", "SRE Team"},
                        {"HSM Root Key", "RSA-OAEP", "4096-bit", "730 days", "SecOps"}
                };
                for (int r = 0; r < table2.length; r++) {
                    PDType1Font f = r == 0 ? bold : regular;
                    for (int c = 0; c < table2[r].length; c++) {
                        writeLine(cs, f, 9.5f, colX2[c], y, table2[r][c]);
                    }
                    y -= 20;
                }

                y -= 18;
                writeLine(cs, bold, 12, 50, y, "2.1 Vulnerability Remediation SLA");
                y -= 18;
                for (String line : wrap(
                        "Vulnerability scan results from Trivy, Dependabot, and OWASP Dependency-Check are ingested "
                                + "daily into the security dashboard. Critical CVEs (CVSS >= 9.0) trigger an immediate "
                                + "pager alert and must be patched within 72 hours. Any exception requires written CISO "
                                + "sign-off with compensating WAF or network isolation controls documented in the audit trail.",
                        84)) {
                    writeLine(cs, regular, 10, 50, y, line);
                    y -= 14;
                }
                writeLine(cs, regular, 9, 275, 35, "Page 2 of 2");
            }

            doc.save(target.toFile());
        }
    }

    private static void writeTwoColumnPdf(Path target) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            // Page 1: Title + Abstract (full width) + 2 columns
            PDPage p1 = new PDPage(PDRectangle.A4);
            doc.addPage(p1);
            List<String> p1Left = buildColumnLines(List.of(
                    "1. Introduction to Zero Trust",
                    "Traditional perimeter-based network security assumes that systems inside the corporate "
                            + "firewall can be implicitly trusted. Modern microservice architectures, hybrid cloud "
                            + "deployments, and remote engineering teams render that assumption obsolete. Once an "
                            + "attacker compromises a single endpoint through phishing or a vulnerable dependency, "
                            + "flat internal networks allow rapid lateral movement toward sensitive databases.",
                    "Zero-Trust Architecture (ZTA), formalized in NIST SP 800-207, replaces implicit network "
                            + "trust with continuous, explicit verification of every request. Every user, device, and "
                            + "service must authenticate cryptographically and receive only the minimum authorization "
                            + "required for the specific transaction.",
                    "2. Identity-Aware Micro-Segmentation",
                    "In a containerized platform such as EduPulse, network policies isolate each microservice "
                            + "so that only the API Gateway is reachable from external clients. Internal services "
                            + "communicate exclusively over authenticated channels verified with short-lived mTLS "
                            + "certificates issued by an internal Certificate Authority."
            ), 41);
            List<String> p1Right = buildColumnLines(List.of(
                    "3. Protecting RAG Data Pipelines",
                    "Retrieval-Augmented Generation systems introduce distinct attack surfaces beyond classic "
                            + "web vulnerabilities. Indirect prompt injection occurs when malicious instructions are "
                            + "embedded inside external web pages or uploaded PDF documents to hijack model output "
                            + "during retrieval. Sanitizing ingested content and enforcing strict metadata tenant "
                            + "filters prevent cross-tenant data leakage.",
                    "Server-Side Request Forgery (SSRF) is another high-impact risk during dynamic ingestion. "
                            + "If a crawler accepts arbitrary URLs without DNS rebinding checks and private-IP "
                            + "blocking, attackers can probe internal service registries or cloud instance metadata "
                            + "endpoints to steal IAM credentials.",
                    "4. Continuous Policy Enforcement",
                    "A Policy Enforcement Point (PEP) at the API Gateway validates JWT signatures, checks "
                            + "token expiration, and forwards immutable tenant claims downstream. Each retrieval "
                            + "query converts those claims into validated vector store filter expressions so raw "
                            + "user strings never reach the PostgreSQL query layer."
            ), 41);

            try (PDPageContentStream cs = new PDPageContentStream(doc, p1)) {
                writeLine(cs, bold, 13.5f, 55, 785,
                        "Zero-Trust Architecture for Regulated AI and RAG Platforms");
                writeLine(cs, regular, 9.5f, 55, 765,
                        "Abstract: This technical brief examines Zero-Trust principles, mTLS micro-segmentation, and");
                writeLine(cs, regular, 9.5f, 55, 752,
                        "ingestion security controls required to protect enterprise Retrieval-Augmented Generation systems.");

                renderColumn(cs, bold, regular, 50, 720, p1Left);
                renderColumn(cs, bold, regular, 315, 720, p1Right);
                writeLine(cs, regular, 9, 275, 35, "Page 1 of 2");
            }

            // Page 2: Two columns continuing the paper
            PDPage p2 = new PDPage(PDRectangle.A4);
            doc.addPage(p2);
            List<String> p2Left = buildColumnLines(List.of(
                    "5. Cryptographic Telemetry and Auditing",
                    "Zero-Trust deployments rely on high-fidelity observability to detect anomalous behavior "
                            + "before data exfiltration succeeds. Every authentication decision, denied access "
                            + "attempt, document ingestion event, and vector query should emit structured JSON logs "
                            + "correlated by a distributed trace identifier.",
                    "When combined with automated anomaly detection in a SIEM, unusual patterns such as a "
                            + "service account querying thousands of vector chunks outside business hours trigger "
                            + "automatic token revocation and alert the on-call security engineer.",
                    "6. Supply Chain Hardening",
                    "Container images should use minimal runtime base images, run as non-root system users, "
                            + "and mount configuration or document volumes read-only whenever write access is not "
                            + "required. Reproducible builds paired with signed Software Bills of Materials (SBOMs) "
                            + "enable rapid impact analysis when a new zero-day CVE is disclosed."
            ), 41);
            List<String> p2Right = buildColumnLines(List.of(
                    "7. Threat Modeling for Vector Stores",
                    "Embeddings stored in pgvector retain substantial semantic information about the source "
                            + "documents. Treating the vector table as non-sensitive is a critical security mistake. "
                            + "Organizations must enforce row-level or metadata-level tenant isolation, encrypt "
                            + "database volumes at rest with AES-256, and restrict direct PostgreSQL network access "
                            + "to localhost or private container networks.",
                    "8. Conclusion and Operational Readiness",
                    "Adopting Zero-Trust controls across ingestion, storage, and retrieval pipelines allows "
                            + "regulated enterprises in finance, healthcare, and higher education to deploy AI "
                            + "assistants without sacrificing compliance. By verifying every identity, validating "
                            + "every filter, sanitizing every document, and logging every decision, teams achieve "
                            + "verifiable audit readiness and resilient defense in depth."
            ), 41);

            try (PDPageContentStream cs = new PDPageContentStream(doc, p2)) {
                renderColumn(cs, bold, regular, 50, 770, p2Left);
                renderColumn(cs, bold, regular, 315, 770, p2Right);
                writeLine(cs, regular, 9, 275, 35, "Page 2 of 2");
            }

            doc.save(target.toFile());
        }
    }

    private static List<String> buildColumnLines(List<String> blocks, int maxChars) {
        List<String> out = new ArrayList<>();
        for (String block : blocks) {
            boolean heading = block.length() < 48 && !block.endsWith(".");
            if (heading) {
                out.add("##" + block);
            } else {
                out.addAll(wrap(block, maxChars));
                out.add("");
            }
        }
        return out;
    }

    private static void renderColumn(PDPageContentStream cs, PDType1Font bold, PDType1Font regular,
                                     float x, float startY, List<String> lines) throws IOException {
        float y = startY;
        for (String line : lines) {
            if (line.isEmpty()) {
                y -= 6;
            } else if (line.startsWith("##")) {
                writeLine(cs, bold, 10.5f, x, y, line.substring(2));
                y -= 15;
            } else {
                writeLine(cs, regular, 9.5f, x, y, line);
                y -= 13.5f;
            }
        }
    }

    private static void writeLine(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String text)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    private static List<String> wrap(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return List.of(text);
        }
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (!current.isEmpty() && current.length() + 1 + word.length() > maxChars) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(word);
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }
}
