-- English translation of the v0 original published by V4; it shares the original's version row.
-- The effective date is the original's stored date, not the time this migration runs.
-- Once applied, publish subsequent changes in a new versioned migration.
INSERT INTO terms_version_translations (
    terms_version_id, locale, title, content
)
SELECT
    id,
    'en',
    'Lore Sentry Terms of Service',
    $terms$Article 1. Purpose and service

These Terms set out the conditions for using Lore Sentry, provided by the SOMA Lore Sentry team (the “Operator”), and the rights and obligations of the Operator and users. Lore Sentry is a service that helps users write, save and edit novels, and analyze and review the characters, relationships, events and other elements of their manuscripts. The Operator is a team that has not yet registered as a business and does not refer to the organization that runs SOMA itself.

Article 2. Sign-up and accounts

After Google account authentication, a service account is created or an existing account is confirmed. The service agreement is formed when a user agrees to these Terms and applies to use the service, and the service confirms the application. If a user closes the screen on first use without agreeing to the Terms, the account is kept, but the user cannot complete login or use the service.

Members must keep their accounts secure and notify the Operator if they suspect unauthorized use of their account. The Operator does not, as a blanket rule, shift to members the responsibility for account breaches that occur through no fault of their own.

The service is provided for users aged 14 or older. Persons under the age of 14 may not sign up for or use the service.

Article 3. Rights to manuscripts and processing to provide the service

Rights to the manuscripts and attached images that users have written or lawfully hold remain with the respective rights holders. Storing a manuscript in the service does not by itself transfer its copyright to the Operator.

Users permit the Operator to store, copy and process their manuscripts, and to analyze them through external AI, to the extent necessary for the Operator to provide the editor features to them. The purpose and scope of this permission are limited to keeping manuscripts, editing them and providing analysis results.

The Operator does not disclose users’ manuscripts, attached images or analysis results to other users, and does not use them for advertising, for sale or for training the Operator’s models. Processing by external providers that is necessary to provide the service is described in Article 4 and the Privacy Policy (https://loresentry.com/policies/privacy.html).

Article 4. External AI analysis

When a user requests analysis by pressing the analysis button, the text of the novel to be analyzed is sent to the OpenAI API. Saving a manuscript alone does not trigger automatic analysis. Member email addresses, display names, Google account identifiers, internal user IDs and login authentication information are not sent with it.

The Operator does not permit the optional sharing that would provide manuscripts and analysis results to OpenAI as model training data. Not using data for training and whether an external provider retains the data are separate conditions. Retention periods, processing countries and deletion conditions follow the external AI section of the Privacy Policy (https://loresentry.com/policies/privacy.html). These Terms do not permit external providers to use manuscripts for any purpose of their own without limitation.

AI analysis may contain errors or omissions, so users review the analysis results and use them in their creative work. A notice about the limitations of analysis results does not release the Operator from all legal liability.

Article 5. Personal information

The Operator processes member information and personal information contained in manuscripts in accordance with applicable laws and the Privacy Policy. The Privacy Policy describes the purposes, items, retention periods, external processing and how to exercise rights. Consent to these Terms is not used as a substitute for any separately required consent to the collection, use, provision or overseas transfer of personal information.

Article 6. User obligations and restrictions on use

Users must not use another person’s account without authorization, access other users’ private data, or interfere with the normal operation of the service. Users must have the right to store and analyze the materials they enter into the service, and must not infringe the copyright, personal information or other rights of others.

The Operator may restrict use to the extent necessary to respond to violations of laws or these Terms, or to security breaches. In that case, the Operator informs the user of the reason, the scope and period of the restriction, and how to object. If advance notice is difficult because of an urgent security response, the Operator gives notice without delay after taking action. Objections are received through the contact details in Article 10.

Article 7. Manuscript deletion and withdrawal

Users may request the deletion of their manuscripts and withdrawal from membership. Moving items to the trash is a feature that keeps materials so that they can be restored, and is distinct from permanent deletion. When a work is permanently deleted, the Operator also removes its linked document versions, attached images and analysis results, and upon withdrawal, the Operator destroys information according to the standards in the Privacy Policy. If any information must be retained under law, the legal basis, items and period of retention are stated in the Privacy Policy.

Deletion, withdrawal and manuscript export can be requested through the inquiry email address in Article 10. Where the service screens provide a corresponding feature, requests can also be made through that feature. Requests through the inquiry email address are possible even if the user cannot log in or has not agreed to new Terms. To prevent another person’s manuscripts from being deleted or handed over, the Operator performs the minimum identity verification needed to process requests.

After confirming the requester’s identity and the details of the request, the Operator processes requests for permanent deletion or withdrawal without delay and without a separate recovery grace period, and notifies the user of the result. Upon withdrawal, the Operator deletes the member account, Google connection information, terms consent records, and the manuscripts, document versions, attached images, analysis results and workspace information of all projects, and revokes login sessions and pending consent requests. The Operator does not keep separate backups of member accounts or manuscripts. Information that must be retained separately under law and copies retained by external providers are handled according to the Privacy Policy.

When a user requests manuscript export, the Operator verifies the user’s identity and then provides the stored manuscripts as readable text files. Users who need an export can request it before deletion or withdrawal, and if export and deletion are requested together, the Operator confirms with the user the order in which they are carried out. Users are not obliged to export first in order to delete their manuscripts or withdraw. Before permanent deletion, the Operator informs the user of what will be deleted and that it cannot be recovered, and the Operator does not provide a feature to recover manuscripts whose permanent deletion is complete. Materials that could be restored from the trash or document versions also cannot be restored once withdrawal is complete.

Article 8. Service changes, suspension and costs

The Operator may temporarily suspend or change features for maintenance, incident response or service changes. Scheduled maintenance is announced in advance with its schedule and impact, and feature changes are, as a rule, announced 7 days in advance. Changes that significantly affect the use of manuscripts or users’ rights, and termination of the service, are announced at least 30 days in advance through a service notice and to the registered email address. Where advance notice is impossible, such as for urgent security measures or outages, the Operator gives notice of the reason and impact as soon as they are confirmed.

A notice of service termination includes the termination date, the method and period for requesting manuscript export, and the schedule for deleting information. Users can request manuscript export from the date of the notice until the termination date, and the Operator also supports export through the inquiry procedure in Article 7.

The service is currently provided free of charge and has no payment features. If paid features are introduced, the Operator announces the features concerned, the fees and the conditions in advance and accepts separate applications for them. Existing use is not automatically converted to paid use.

Article 9. Liability and disputes

The Operator and users are each liable, in accordance with applicable laws, for damage caused by reasons attributable to them. The Operator’s liability is not excluded across the board merely on the grounds of external AI use, outages or data loss. In particular, the Operator is not exempted from liability for its intentional misconduct or gross negligence.

Efforts are made to resolve disputes related to the service through the inquiry and objection procedures, and applicable laws apply to disputes that are not resolved. Jurisdiction over lawsuits follows the standards set by law.

Article 10. Contact and changes to the Terms

- Operator: SOMA Lore Sentry team
- Email for inquiries, deletion requests and objections: tmdwn0509@gmail.com
- If an in-service contact feature is provided, requests can also be submitted through it. If logging in is difficult, the email address above can be used.

When the Terms are changed, the Operator announces the changes, the reasons and the effective date in the service at least 7 days in advance. Changes that are unfavorable to users or that significantly affect their rights and obligations are announced at least 30 days in advance and are also notified to the registered email address. The current Terms can be viewed in the service, and previous versions can be requested through the inquiry email address.

If a user has not agreed to the latest Terms currently in effect when logging in, the text of the Terms is shown and the user is asked to agree. That login is completed only after the user agrees. Agreement to Terms that are scheduled to take effect is not required before they take effect, and existing login sessions are not ended immediately merely because the Terms have changed. A user is not deemed to have agreed to new Terms merely because the user did not respond after a notice or continued to use the service.

Users may decline to agree to changed Terms. Accounts and manuscripts are not automatically deleted merely because a user does not agree, and the user can request manuscript export or withdrawal through the inquiry procedure in Article 7. The version agreed to and the time of agreement are recorded separately and are deleted upon withdrawal.

Terms version: v0
Effective date: $terms$ || to_char(effective_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD')
FROM terms_versions
WHERE id = 'b226d203-1e9f-4435-8dc8-7a2dc9fcd505';
