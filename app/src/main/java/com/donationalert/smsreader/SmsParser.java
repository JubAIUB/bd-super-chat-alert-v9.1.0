package com.donationalert.smsreader;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses bKash and Nagad SMS messages — "Smart Pipe" version.
 *
 * Classifies ALL transaction SMS into types:
 *   send       — Money received from a person
 *   cashin     — Cash In, deposit, add money from bank
 *   payment    — Payment received from person, payment to merchant, bill pay
 *   remittance — International remittance
 *   other      — Cashback, loan, promo, etc.
 *
 * REJECTS only: OTP/PIN/verification codes (security — never sent to server)
 */
public class SmsParser {

    public static class DonationData {
        public String provider;
        public String amount;
        public String senderNumber;
        public String maskedNumber;
        public String transactionId;
        public String reference;
        public String smsType;      // send, cashin, payment, remittance, other
        public String rawMessage;
        public long timestamp;

        public boolean isValid() {
            return provider != null && amount != null && transactionId != null && smsType != null;
        }

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"provider\":\"").append(esc(provider)).append("\",");
            sb.append("\"amount\":\"").append(esc(amount)).append("\",");
            sb.append("\"sender_number\":\"").append(esc(maskedNumber != null ? maskedNumber : "")).append("\",");
            sb.append("\"sender_full_number\":\"").append(esc(senderNumber != null ? senderNumber : "")).append("\",");
            sb.append("\"transaction_id\":\"").append(esc(transactionId)).append("\",");
            sb.append("\"reference\":\"").append(esc(reference != null ? reference : "")).append("\",");
            sb.append("\"sms_type\":\"").append(esc(smsType)).append("\",");
            sb.append("\"timestamp\":").append(timestamp);
            sb.append("}");
            return sb.toString();
        }

        private String esc(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"")
                     .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        }
    }

    /**
     * Parse SMS. Returns null ONLY for non-bKash/Nagad messages or OTP/PIN.
     * All financial transactions are classified and returned.
     */
    public static DonationData parse(String sender, String body) {
        if (body == null || body.isEmpty()) return null;

        String bodyTrimmed = body.trim();

        // Detect provider first
        String provider = detectProvider(sender, body);
        if (provider == null) return null;

        // REJECT: OTP / PIN / Verification codes (security — never forward to server)
        if (isOtpOrPin(bodyTrimmed)) return null;

        DonationData data = new DonationData();
        data.provider = provider;
        data.rawMessage = body;
        data.timestamp = System.currentTimeMillis();

        // Classify SMS type and parse accordingly
        String type = classifyType(provider, bodyTrimmed);
        data.smsType = type;

        // Parse based on provider and type
        if ("bKash".equals(provider)) {
            parseBkash(data, bodyTrimmed);
        } else {
            parseNagad(data, bodyTrimmed);
        }

        return data.isValid() ? data : null;
    }

    // ================================================================
    // TYPE CLASSIFICATION
    // ================================================================

    private static String classifyType(String provider, String body) {
        String lower = body.toLowerCase();

        if ("bKash".equals(provider)) {
            // Order matters — check specific patterns before generic ones

            // Remittance: "You have received remittance."
            if (lower.startsWith("you have received remittance"))
                return "remittance";

            // Cash In: "Cash In Tk X from ..."
            if (lower.startsWith("cash in tk"))
                return "cashin";

            // Deposit: "You have received deposit of Tk X from VISA Card"
            if (lower.contains("received deposit"))
                return "cashin";

            // Payment received: "You have received payment Tk X from 01..."
            if (lower.startsWith("you have received payment"))
                return "payment";

            // Payment sent: "Payment of Tk X to ..." or "Payment Tk X to ..."
            if (lower.startsWith("payment of tk") || lower.startsWith("payment tk"))
                return "payment";

            // Bill Pay: "Bill successfully paid."
            if (lower.startsWith("bill successfully paid"))
                return "payment";

            // Send Money: "You have received Tk X from 01..."
            // Must check AFTER remittance/payment/deposit to avoid false matches
            if (lower.startsWith("you have received tk") && lower.contains("from 01"))
                return "send";

            // Cashback, Loan, etc. → other
            return "other";

        } else {
            // Nagad

            // Money Received: "Money Received."
            if (lower.startsWith("money received"))
                return "send";

            // Cash In: "Cash In Received."
            if (lower.startsWith("cash in received"))
                return "cashin";

            // Add Money from Bank
            if (lower.startsWith("add money from bank"))
                return "cashin";

            // Payment: "Payment to '...'"
            if (lower.startsWith("payment to"))
                return "payment";

            // Cashback, promo → other
            return "other";
        }
    }

    private static boolean isOtpOrPin(String body) {
        String lower = body.toLowerCase();
        if (lower.contains("verification code")) return true;
        if (lower.contains("your otp")) return true;
        if (lower.contains("one time password")) return true;
        if (lower.contains("never share your otp")) return true;
        if (lower.contains("account binding")) return true;
        // Bengali promo (non-latin primary content)
        if (isMostlyNonLatin(body)) return true;
        return false;
    }

    private static boolean isMostlyNonLatin(String text) {
        if (text.length() < 10) return false;
        int nonLatin = 0;
        int total = 0;
        for (char c : text.toCharArray()) {
            if (Character.isLetter(c)) {
                total++;
                if (c > 0x024F) nonLatin++; // Beyond extended Latin
            }
        }
        return total > 0 && ((float) nonLatin / total) > 0.5f;
    }

    // ================================================================
    // bKash PARSING (single-line format)
    // ================================================================

    private static void parseBkash(DonationData data, String body) {
        // Extract amount — multiple patterns
        data.amount = extractAmount(body, new String[]{
            "received\\s+payment\\s+Tk\\s+([\\d,]+\\.?\\d*)", // You have received payment Tk X
            "received\\s+Tk\\s+([\\d,]+\\.?\\d*)",     // You have received Tk X
            "Cash\\s+In\\s+Tk\\s+([\\d,]+\\.?\\d*)",   // Cash In Tk X
            "Payment\\s+(?:of\\s+)?Tk\\s+([\\d,]+\\.?\\d*)", // Payment of Tk X / Payment Tk X
            "Total:\\s*Tk\\s+([\\d,]+\\.?\\d*)",        // Total: Tk X (remittance)
            "Cashback\\s+Tk\\s+([\\d,]+\\.?\\d*)",      // Cashback Tk X
            "deposit\\s+of\\s+Tk\\s+([\\d,]+\\.?\\d*)", // deposit of Tk X
            "Amount:\\s*Tk\\s*([\\d,]+\\.?\\d*)",        // Amount: Tk X (bill pay)
            "Repayment\\s+of\\s+Tk\\s+([\\d,]+\\.?\\d*)", // Loan Repayment of Tk X
        });

        // Extract sender number (01XXXXXXXXX) — for send/cashin types
        data.senderNumber = extractPattern(body, "from\\s+(?:A/C\\s+)?(01[\\d]{9})");
        if (data.senderNumber != null) {
            data.maskedNumber = "********" + data.senderNumber.substring(data.senderNumber.length() - 3);
        }

        // Extract TrxID
        data.transactionId = extractPattern(body,
            "(?:Tr(?:x|an(?:saction)?)ID|TxnID)[:\\s]+([A-Za-z0-9]+)");

        // Extract Ref message (bKash format: "Ref ... . Fee" or "Ref ... . Balance")
        if ("send".equals(data.smsType)) {
            Matcher refMatcher = Pattern.compile(
                "Ref\\s+(.+?)(?:\\.\\s*(?:Fee|Balance|Tr(?:x|an)ID|TxnID)|$)",
                Pattern.CASE_INSENSITIVE
            ).matcher(body);
            if (refMatcher.find()) {
                String ref = refMatcher.group(1).trim().replaceAll("[.\\s]+$", "");
                if (!ref.isEmpty()) data.reference = ref;
            }
        }
    }

    // ================================================================
    // NAGAD PARSING (multi-line format)
    // ================================================================

    private static void parseNagad(DonationData data, String body) {
        // Extract amount
        data.amount = extractAmount(body, new String[]{
            "Amount:\\s*Tk\\s*([\\d,]+\\.?\\d*)",       // Amount: Tk X
        });

        // Extract sender number
        String senderNum = extractPattern(body, "Sender:\\s*(01[\\d]{9})");
        if (senderNum == null) senderNum = extractPattern(body, "Uddokta:\\s*(01[\\d]{9})");
        data.senderNumber = senderNum;
        if (data.senderNumber != null) {
            data.maskedNumber = "********" + data.senderNumber.substring(data.senderNumber.length() - 3);
        }

        // Extract TxnID
        data.transactionId = extractPattern(body,
            "(?:TxnID|TrxID)[:\\s]+([A-Za-z0-9]+)");

        // Extract Ref (Nagad multi-line: "Ref: ...")
        if ("send".equals(data.smsType)) {
            Matcher refMatcher = Pattern.compile(
                "Ref:\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
            ).matcher(body);
            if (refMatcher.find()) {
                String ref = refMatcher.group(1).trim().replaceAll("[.\\s]+$", "");
                if (!ref.isEmpty() && !ref.equalsIgnoreCase("N/A")) {
                    data.reference = ref;
                }
            }
        }
    }

    // ================================================================
    // HELPERS
    // ================================================================

    private static String extractAmount(String body, String[] patterns) {
        for (String pat : patterns) {
            Matcher m = Pattern.compile(pat, Pattern.CASE_INSENSITIVE).matcher(body);
            if (m.find()) {
                return m.group(1).replace(",", "");
            }
        }
        return null;
    }

    private static String extractPattern(String body, String pattern) {
        Matcher m = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private static String detectProvider(String sender, String body) {
        String senderLower = sender != null ? sender.toLowerCase() : "";

        // STRICT: Only accept SMS from real bKash/Nagad senders
        // No body fallback — prevents fake SMS from random numbers
        if (senderLower.contains("bkash"))
            return "bKash";
        if (senderLower.contains("nagad"))
            return "Nagad";

        return null;
    }
}
