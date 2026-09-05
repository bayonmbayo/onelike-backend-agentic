package com.onelike.app.support;

public class EmailTemplates {
    public static final String GDPRConfirmation  = """
        <html><body>
        Dear $FIRSTNAME$ $LASTNAME$,
        <br/><br/>
        Thank you for reaching out to us with your General Data Protection Regulation (GDPR) request. We are writing to confirm that we have received your request.
        <br/><br/>
        We take your privacy and data protection rights very seriously. Our team is currently reviewing your request and will ensure that it is handled in accordance with the GDPR regulations.
        <br/><br/>
        Please allow us some time to process your request. We aim to respond to all GDPR requests promptly, but it may take up to 30 days to complete this process, depending on the complexity and nature of the request.
        <br/><br/>
        If you have any further questions or need additional assistance in the meantime, please do not hesitate to contact us at <a href="mailto:gdpr@onelike.app">gdpr@onelike.app</a>.
        <br/><br/>
        Thank you for your patience and understanding.
        <br/><br/>
        Best regards,
        <br/><br/>
        The OneLike Team
        </body></html>
        """;
    public static final String GDPRRequestNotification = """
         <html><body>
         Dear Friend,
         <br/><br/>
         This is an automated notification to inform you that a new General Data Protection Regulation (GDPR) request has been submitted by a user.
         <br/><br/>
         <b>Request Details:</b>
         <br/><br/>
         <ul>
         <li>User Name: $FIRSTNAME$ $LASTNAME$</li>
         <li>User Contact Email: $EMAIL$</li>
         <li>Date and Time of Request: $TIME$</li>
         <li>Nature of Request: $TYPE$</li>
         </ul>
         <br/><br/>
         <b>Action Required:</b>
         <p>Please review this request at your earliest convenience and initiate the necessary procedures to address it in compliance with GDPR regulations. Remember, GDPR requests should be handled within 30 days from the date of receipt.</p>
         <br/>
         <b>Tracking and Compliance:</b>
         <p>To ensure proper tracking and compliance, please document all actions taken in response to this request in our GDPR compliance system.</p>
         <br/>
         <b>Further Assistance:</b>
         <p>If you require any further information or assistance in handling this request, please contact <a href="mailto:gdpr@onelike.app">gdpr@onelike.app</a>.</p>
         <br/>
         This notification is part of the automated GDPR compliance workflow and is intended to streamline and ensure the timely processing of user requests.
         <br/><br/>
         Thank you for your prompt attention to this matter.
         <br/><br/>
         Best regards,
         <br/><br/>
         The OneLike Notification System
         </body></html>
         """;
}

